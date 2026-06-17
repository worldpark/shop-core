package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SellerFulfillmentFacade} 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>소유권 매트릭스: 판매자A는 자기 owner_id 항목만 생성/시작/완료</li>
 *   <li>타 판매자/미존재 항목 지정 → 404(존재 은닉)</li>
 *   <li>타 판매자·admin 생성 shipment ship/deliver → 404(존재 은닉)</li>
 *   <li>seller_id 스탬프: 생성 배송의 seller_id == sellerId</li>
 *   <li>미지정 생성 = 판매자 owned 미발송 항목만</li>
 *   <li>대상 0건 → 409(상태충돌)</li>
 *   <li>멀티셀러 주문: A·B 각자 독립 배송(seller_id 각자), 모두 deliver해야 order delivered(rollup 재사용)</li>
 *   <li>판매자 ship → ShippingStartedEvent 경로(이벤트 발행) 재사용</li>
 *   <li>admin 경로 불변(seller_id=null 유지)</li>
 * </ul>
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
class SellerFulfillmentFacadeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private SellerFulfillmentFacade sellerFulfillmentFacade;

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private JdbcTemplate jdbc;

    // ============================================================
    // 1. 배송 생성 — seller_id 스탬프 + 소유권 스코핑
    // ============================================================

    @Test
    @DisplayName("판매자 배송 생성 → seller_id 스탬프 확인 (DB 검증)")
    void createShipment_sellerIdStamped() {
        long sellerAId = insertUser("seller-stamp-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-stamp-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrder(buyerId, sellerAId, "스탬프상품");

        ShipmentResponse response = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);

        assertThat(response.status()).isEqualTo("preparing");

        // DB 검증: seller_id 스탬프
        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, response.shipmentId());
        assertThat(storedSellerId).isEqualTo(sellerAId);
    }

    @Test
    @DisplayName("미지정 생성 — 그 판매자 소유 미발송 항목만 묶임 (타 판매자 항목 제외)")
    void createShipment_noItemIds_onlyOwnedItems() {
        long sellerAId = insertUser("seller-scope-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-scope-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-scope-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrderBase(buyerId);
        long itemAId = insertOrderItem(orderId, sellerAId, "A상품");
        long itemBId = insertOrderItem(orderId, sellerBId, "B상품");

        ShipmentResponse response = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);

        // 배송 항목에 A만 포함
        List<Long> shipmentItemOrderItemIds = getShipmentItemOrderItemIds(response.shipmentId());
        assertThat(shipmentItemOrderItemIds).containsExactly(itemAId);
        assertThat(shipmentItemOrderItemIds).doesNotContain(itemBId);
    }

    @Test
    @DisplayName("타 판매자 항목 지정 → 404 존재 은닉")
    void createShipment_otherSellerItem_404() {
        long sellerAId = insertUser("seller-idor-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-idor-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-idor-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrderBase(buyerId);
        long itemBId = insertOrderItem(orderId, sellerBId, "B상품");

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, List.of(itemBId)))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    @DisplayName("소유 항목 없음(대상 0건) → 409 상태충돌")
    void createShipment_noOwnedItems_409() {
        long sellerAId = insertUser("seller-empty-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-empty-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-empty-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerBId, "B상품만");

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null))
                .isInstanceOf(OrderFulfillmentConflictException.class);
    }

    @Test
    @DisplayName("admin 경로 배송 생성 → seller_id=null 유지 (admin 경로 불변)")
    void createShipment_adminPath_sellerIdNull() {
        long buyerId = insertUser("buyer-admin-" + System.nanoTime() + "@test.com", "CONSUMER");
        long variantId = insertVariantForAdmin();
        long orderId = insertPaidOrderWithVariant(buyerId, variantId, "어드민상품");

        ShipmentResponse response = orderFulfillmentService.createShipment(orderId, null);

        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, response.shipmentId());
        assertThat(storedSellerId).isNull();
    }

    // ============================================================
    // 2. 배송 시작 — 소유권 검사 + stale-read 가드
    // ============================================================

    @Test
    @DisplayName("판매자 배송 시작 → ShipmentResponse 반환 + shipment shipping 전이")
    void ship_sellerOwned_success() {
        long sellerAId = insertUser("seller-ship-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-ship-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrder(buyerId, sellerAId, "배송시작상품");
        ShipmentResponse created = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        long shipmentId = created.shipmentId();

        ShipmentResponse shipped = sellerFulfillmentFacade.ship(
                sellerAEmail, shipmentId, "CJ대한통운", "1234567890");

        assertThat(shipped.status()).isEqualTo("shipping");
        assertThat(shipped.carrier()).isEqualTo("CJ대한통운");
        assertThat(shipped.trackingNumber()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("타 판매자 배송 ship 시도 → 404 존재 은닉")
    void ship_otherSellerShipment_404() {
        long sellerAId = insertUser("seller-ship-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-ship-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        String sellerBEmail = getEmail(sellerBId);
        long buyerId = insertUser("buyer-ship-b-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrder(buyerId, sellerBId, "B배송상품");
        ShipmentResponse created = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);
        long shipmentId = created.shipmentId();

        // 판매자A가 판매자B의 배송을 ship 시도 → 404
        assertThatThrownBy(() ->
                sellerFulfillmentFacade.ship(sellerAEmail, shipmentId, "CJ대한통운", "111"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    @DisplayName("admin 생성 배송(seller_id=null) ship 시도 → 404 존재 은닉")
    void ship_adminCreatedShipment_404() {
        long sellerAId = insertUser("seller-admin-ship-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-admin-ship-" + System.nanoTime() + "@test.com", "CONSUMER");

        long variantId = insertVariantForAdmin();
        long orderId = insertPaidOrderWithVariant(buyerId, variantId, "어드민상품");
        // admin 경로로 배송 생성 (seller_id=null)
        ShipmentResponse adminShipment = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = adminShipment.shipmentId();

        // 판매자A가 admin 배송 ship 시도 → 404
        assertThatThrownBy(() ->
                sellerFulfillmentFacade.ship(sellerAEmail, shipmentId, "CJ대한통운", "999"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 3. 배송 완료 — 소유권 검사 + rollup 재사용
    // ============================================================

    @Test
    @DisplayName("판매자 배송 완료 → DeliverResponse 반환")
    void deliver_sellerOwned_success() {
        long sellerAId = insertUser("seller-deliver-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        long buyerId = insertUser("buyer-deliver-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrder(buyerId, sellerAId, "배송완료상품");
        ShipmentResponse created = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        long shipmentId = created.shipmentId();
        sellerFulfillmentFacade.ship(sellerAEmail, shipmentId, "CJ대한통운", "9999");

        DeliverResponse result = sellerFulfillmentFacade.deliver(sellerAEmail, shipmentId);

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isTrue();
    }

    @Test
    @DisplayName("타 판매자 배송 deliver 시도 → 404 존재 은닉")
    void deliver_otherSellerShipment_404() {
        long sellerAId = insertUser("seller-del-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-del-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        String sellerBEmail = getEmail(sellerBId);
        long buyerId = insertUser("buyer-del-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrder(buyerId, sellerBId, "B완료상품");
        ShipmentResponse created = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);
        long shipmentId = created.shipmentId();
        sellerFulfillmentFacade.ship(sellerBEmail, shipmentId, "롯데택배", "2222");

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.deliver(sellerAEmail, shipmentId))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 4. 멀티셀러 주문 — 각자 배송 + deliver-when-all rollup 재사용
    // ============================================================

    @Test
    @DisplayName("멀티셀러 주문: A·B 각자 배송 생성·시작·완료 → 모두 완료 시 order delivered")
    void multiSeller_eachDelivers_orderDeliveredOnlyWhenBothDone() {
        long sellerAId = insertUser("seller-multi-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-multi-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        String sellerBEmail = getEmail(sellerBId);
        long buyerId = insertUser("buyer-multi-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerAId, "A멀티상품");
        insertOrderItem(orderId, sellerBId, "B멀티상품");

        // 판매자A 배송 생성 (paid → preparing)
        ShipmentResponse aShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        // 판매자B 배송 생성 (preparing → preparing, 주문 status 불변)
        ShipmentResponse bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        // 판매자A 배송 시작 (preparing → shipping)
        sellerFulfillmentFacade.ship(sellerAEmail, aShipment.shipmentId(), "CJ대한통운", "A001");
        // 판매자B 배송 시작 (preparing → shipping)
        sellerFulfillmentFacade.ship(sellerBEmail, bShipment.shipmentId(), "롯데택배", "B001");

        // 판매자A만 완료 → orderDelivered false (B 아직)
        DeliverResponse aDelivered = sellerFulfillmentFacade.deliver(sellerAEmail, aShipment.shipmentId());
        assertThat(aDelivered.orderDelivered()).isFalse();

        String orderStatusAfterA = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterA).isEqualTo("shipping");

        // 판매자B 완료 → orderDelivered true (모두 완료)
        DeliverResponse bDelivered = sellerFulfillmentFacade.deliver(sellerBEmail, bShipment.shipmentId());
        assertThat(bDelivered.orderDelivered()).isTrue();

        String orderStatusAfterB = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterB).isEqualTo("delivered");

        // 각 배송의 seller_id 확인
        Long sellerIdA = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, aShipment.shipmentId());
        Long sellerIdB = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, bShipment.shipmentId());
        assertThat(sellerIdA).isEqualTo(sellerAId);
        assertThat(sellerIdB).isEqualTo(sellerBId);
    }

    @Test
    @DisplayName("멀티셀러: 판매자A는 판매자B 배송 생성에 간섭 불가 (독립 생성)")
    void multiSeller_independent_noInterference() {
        long sellerAId = insertUser("seller-ind-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-ind-b-" + System.nanoTime() + "@test.com", "SELLER");
        String sellerAEmail = getEmail(sellerAId);
        String sellerBEmail = getEmail(sellerBId);
        long buyerId = insertUser("buyer-ind-" + System.nanoTime() + "@test.com", "CONSUMER");

        long orderId = insertPaidOrderBase(buyerId);
        long itemAId = insertOrderItem(orderId, sellerAId, "A독립상품");
        long itemBId = insertOrderItem(orderId, sellerBId, "B독립상품");

        // 판매자A 배송 생성
        ShipmentResponse aShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        // 판매자B 배송 생성
        ShipmentResponse bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        assertThat(aShipment.shipmentId()).isNotEqualTo(bShipment.shipmentId());

        List<Long> aItemIds = getShipmentItemOrderItemIds(aShipment.shipmentId());
        List<Long> bItemIds = getShipmentItemOrderItemIds(bShipment.shipmentId());

        assertThat(aItemIds).containsExactly(itemAId);
        assertThat(bItemIds).containsExactly(itemBId);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email, String role) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "테스터", role, "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private String getEmail(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
    }

    /**
     * 판매자 소유 항목이 있는 paid 주문 생성 (variant 직접 삽입).
     */
    private long insertPaidOrder(long buyerId, long sellerId, String productName) {
        long variantId = insertVariantForSeller(sellerId, productName);
        String orderNumber = "ORD-SELLER-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                "수령인", "010-0000-0000", "12345", "서울시");
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items(order_id, variant_id, owner_id, product_name, " +
                    "unit_price, quantity, line_amount) VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, sellerId, productName, amount, 1, amount);
        return orderId;
    }

    private long insertPaidOrderBase(long buyerId) {
        String orderNumber = "ORD-BASE-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("20000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                "수령인", "010-0000-0000", "12345", "서울시");
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    private long insertOrderItem(long orderId, long sellerId, String productName) {
        long variantId = insertVariantForSeller(sellerId, productName);
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO order_items(order_id, variant_id, owner_id, product_name, " +
                    "unit_price, quantity, line_amount) VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, sellerId, productName, amount, 1, amount);
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? AND product_name=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId, productName);
    }

    private long insertVariantForSeller(long sellerId, String productName) {
        String uniqueName = productName + "-" + System.nanoTime();
        long catId = insertCategoryIfNeeded();
        jdbc.update("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)",
                sellerId, catId, uniqueName, new BigDecimal("10000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, uniqueName);
        String sku = "SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock) VALUES(?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertVariantForAdmin() {
        long catId = insertCategoryIfNeeded();
        String name = "어드민상품-" + System.nanoTime();
        jdbc.update("INSERT INTO products(category_id, name, base_price, status) VALUES(?,?,?,?)",
                catId, name, new BigDecimal("10000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, name);
        String sku = "SKU-ADMIN-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock) VALUES(?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertPaidOrderWithVariant(long buyerId, long variantId, String productName) {
        String orderNumber = "ORD-ADMIN-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                "수령인", "010-0000-0000", "12345", "서울시");
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items(order_id, variant_id, product_name, unit_price, quantity, line_amount) " +
                    "VALUES(?,?,?,?,?,?)",
                orderId, variantId, productName, amount, 1, amount);
        return orderId;
    }

    private long categoryCache = -1L;

    private long insertCategoryIfNeeded() {
        if (categoryCache > 0) {
            return categoryCache;
        }
        String slug = "cat-sf-" + System.nanoTime();
        jdbc.update("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)",
                "테스트카테고리" + System.nanoTime(), slug, 1);
        categoryCache = jdbc.queryForObject("SELECT id FROM categories WHERE slug=?", Long.class, slug);
        return categoryCache;
    }

    private List<Long> getShipmentItemOrderItemIds(long shipmentId) {
        return jdbc.queryForList(
                "SELECT order_item_id FROM shipment_items WHERE shipment_id=? ORDER BY id",
                Long.class, shipmentId);
    }
}
