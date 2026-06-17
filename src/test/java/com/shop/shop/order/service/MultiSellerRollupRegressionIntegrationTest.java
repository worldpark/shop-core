package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 멀티셀러 롤업 회귀 통합 테스트 (plan §3.3, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>판매자A·B 항목 혼합 주문 → 각자 배송 생성·ship — 첫 ship에 order preparing→shipping 1회(멱등)</li>
 *   <li>A·B 모두 deliver → order delivered</li>
 *   <li>일부만 deliver → order shipping 유지</li>
 *   <li>ship 멱등: 둘째 판매자 ship 시 order status 불변(shipping)</li>
 * </ul>
 *
 * <p>롤업/이벤트/동시성 코드는 재구현 없이 048/049 기존 코드를 그대로 재사용한다.
 * 이 테스트는 기존 구현의 회귀를 멀티셀러 시나리오로 확인하는 것이 목적이다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"
})
class MultiSellerRollupRegressionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private SellerFulfillmentFacade sellerFulfillmentFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트판매자"));
        when(memberDirectory.findUserIdByEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String email = inv.getArgument(0);
                    return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
                });
        // ship()이 ProductOrderCatalog를 호출할 때 동적으로 스냅샷 반환
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Long> variantIds = inv.getArgument(0);
                    return variantIds.stream()
                            .filter(id -> id != null)
                            .map(variantId -> {
                                Long productId = jdbc.queryForObject(
                                        "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
                                return new OrderableVariantSnapshot(
                                        variantId, productId != null ? productId : 0L,
                                        "상품", null, List.of(),
                                        new BigDecimal("10000"), true, 100, "ON_SALE", true, null);
                            })
                            .toList();
                });
    }

    // ============================================================
    // §3.3-1 멀티셀러: A·B 각자 배송 생성·ship → 첫 ship이 order preparing→shipping 롤업
    // ============================================================

    @Test
    @DisplayName("§3.3 멀티셀러 ship 멱등: 첫 ship 주문 preparing→shipping, 둘째 ship 주문 status 불변")
    void multiSeller_ship_firstShipRollupsOrderToShipping_secondShipNoChange() {
        long sellerA = insertUser("rollup-ship-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("rollup-ship-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("rollup-ship-buyer-" + System.nanoTime() + "@test.com");
        String sellerAEmail = getEmail(sellerA);
        String sellerBEmail = getEmail(sellerB);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerA, "A롤업상품");
        insertOrderItem(orderId, sellerB, "B롤업상품");

        // A·B 각자 배송 생성 (paid → preparing 롤업)
        ShipmentResponse aShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        ShipmentResponse bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        // 판매자A ship — preparing → shipping 롤업 (첫 ship)
        sellerFulfillmentFacade.ship(sellerAEmail, aShipment.shipmentId(), "CJ대한통운", "A-TRK-001");

        String orderStatusAfterAShip = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterAShip).as("첫 ship 후 order preparing→shipping 롤업").isEqualTo("shipping");

        // 판매자B ship — order 이미 shipping, 멱등 (불변)
        sellerFulfillmentFacade.ship(sellerBEmail, bShipment.shipmentId(), "롯데택배", "B-TRK-001");

        String orderStatusAfterBShip = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterBShip).as("둘째 ship 후 order status 불변(shipping)").isEqualTo("shipping");
    }

    // ============================================================
    // §3.3-2 A·B 모두 deliver → order delivered
    // ============================================================

    @Test
    @DisplayName("§3.3 멀티셀러 롤업: A·B 모두 deliver → order delivered")
    void multiSeller_bothDeliver_orderDelivered() {
        long sellerA = insertUser("rollup-del-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("rollup-del-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("rollup-del-buyer-" + System.nanoTime() + "@test.com");
        String sellerAEmail = getEmail(sellerA);
        String sellerBEmail = getEmail(sellerB);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerA, "A전달상품");
        insertOrderItem(orderId, sellerB, "B전달상품");

        ShipmentResponse aShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        ShipmentResponse bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        sellerFulfillmentFacade.ship(sellerAEmail, aShipment.shipmentId(), "CJ대한통운", "A-DEL-001");
        sellerFulfillmentFacade.ship(sellerBEmail, bShipment.shipmentId(), "롯데택배", "B-DEL-001");

        // A만 deliver → orderDelivered false
        DeliverResponse aResult = sellerFulfillmentFacade.deliver(sellerAEmail, aShipment.shipmentId());
        assertThat(aResult.orderDelivered()).as("A만 완료 시 orderDelivered false").isFalse();

        String orderStatusAfterA = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterA).as("A만 완료 시 order 여전히 shipping").isEqualTo("shipping");

        // B도 deliver → orderDelivered true
        DeliverResponse bResult = sellerFulfillmentFacade.deliver(sellerBEmail, bShipment.shipmentId());
        assertThat(bResult.orderDelivered()).as("B까지 완료 시 orderDelivered true").isTrue();

        String orderStatusAfterB = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterB).as("A·B 모두 완료 시 order delivered").isEqualTo("delivered");
    }

    // ============================================================
    // §3.3-3 일부만 deliver → order shipping 유지
    // ============================================================

    @Test
    @DisplayName("§3.3 멀티셀러 부분 deliver: 일부만 deliver → order shipping 유지")
    void multiSeller_partialDeliver_orderRemainsShipping() {
        long sellerA = insertUser("rollup-part-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("rollup-part-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("rollup-part-buyer-" + System.nanoTime() + "@test.com");
        String sellerAEmail = getEmail(sellerA);
        String sellerBEmail = getEmail(sellerB);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerA, "A부분상품");
        insertOrderItem(orderId, sellerB, "B부분상품");

        ShipmentResponse aShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        ShipmentResponse bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        sellerFulfillmentFacade.ship(sellerAEmail, aShipment.shipmentId(), "CJ대한통운", "A-PART-001");
        sellerFulfillmentFacade.ship(sellerBEmail, bShipment.shipmentId(), "롯데택배", "B-PART-001");

        // A만 deliver (B는 shipping 상태 유지)
        DeliverResponse aResult = sellerFulfillmentFacade.deliver(sellerAEmail, aShipment.shipmentId());
        assertThat(aResult.orderDelivered()).isFalse();

        // B 배송은 여전히 shipping
        String bShipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, bShipment.shipmentId());
        assertThat(bShipmentStatus).isEqualTo("shipping");

        // order도 shipping 유지
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).as("B 미완료 시 order shipping 유지").isEqualTo("shipping");
    }

    // ============================================================
    // §3.3-4 배송 생성 시 paid→preparing 롤업 1회 (멱등)
    // ============================================================

    @Test
    @DisplayName("§3.3 배송 생성 멱등: A 배송 생성(paid→preparing), B 배송 생성(preparing 불변)")
    void multiSeller_firstCreate_rollupsOrderToPreparing_secondCreate_noChange() {
        long sellerA = insertUser("rollup-create-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("rollup-create-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("rollup-create-buyer-" + System.nanoTime() + "@test.com");
        String sellerAEmail = getEmail(sellerA);
        String sellerBEmail = getEmail(sellerB);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerA, "A생성상품");
        insertOrderItem(orderId, sellerB, "B생성상품");

        // A 배송 생성 → paid → preparing
        sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        String statusAfterA = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfterA).as("A 배송 생성 후 order preparing").isEqualTo("preparing");

        // B 배송 생성 → preparing 불변
        sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);
        String statusAfterB = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(statusAfterB).as("B 배송 생성 후 order status 불변(preparing)").isEqualTo("preparing");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "테스터", "SELLER", "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private String getEmail(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
    }

    private long insertPaidOrderBase(long buyerId) {
        String orderNumber = "ORD-ROLLUP-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("20000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                "수령인", "010-0000-0000", "12345", "서울시");
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    /**
     * variant_id를 포함한 order_item 삽입 — ship() 경로에서 ProductOrderCatalog 조회에 필요.
     */
    private void insertOrderItem(long orderId, long ownerId, String productName) {
        long variantId = insertVariantForSeller(ownerId, productName);
        String unique = productName + "-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO order_items(order_id, variant_id, product_name, owner_id, unit_price, quantity, line_amount) " +
                    "VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, unique, ownerId, amount, 1, amount);
    }

    private long insertVariantForSeller(long sellerId, String productName) {
        String unique = productName + "-VAR-" + System.nanoTime();
        long catId = insertCategoryIfNeeded();
        jdbc.update("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)",
                sellerId, catId, unique, new BigDecimal("10000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, unique);
        String sku = "SKU-ROLLUP-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock, is_active) VALUES(?,?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100, true);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long categoryCache = -1L;

    private long insertCategoryIfNeeded() {
        if (categoryCache > 0) return categoryCache;
        String slug = "cat-rollup-" + System.nanoTime();
        jdbc.update("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)",
                "롤업테스트카테고리", slug, 1);
        categoryCache = jdbc.queryForObject("SELECT id FROM categories WHERE slug=?", Long.class, slug);
        return categoryCache;
    }
}
