package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.ShippingStartedEvent;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 판매자 배송 시작(ship) → ShippingStartedEvent 발행 종단 스모크 (plan §3.4, Testcontainers).
 *
 * <p>검증: seller ship → ShippingStartedEvent AFTER_COMMIT 발행 → notification 소비 경로 연결.
 * notification 실 SMTP 발송 회피: spring.modulith.events.externalization.enabled=false (Kafka 비활성).
 * 이벤트 발행은 @TransactionalEventListener(AFTER_COMMIT) 캡처 리스너로 단언한다.
 *
 * <p>seller ship이 기존 공용 ship() 경로를 그대로 탄다는 점을 확인하는 종단 스모크다.
 * 판매자 대상 신규 알림은 범위 외(plan §5 비범위).
 *
 * <p>외부 의존 모킹: MemberDirectory, ProductOrderCatalog.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SellerShipNotificationSmokeIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"
})
class SellerShipNotificationSmokeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private SellerFulfillmentFacade sellerFulfillmentFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptureListener captureListener;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        captureListener.clear();
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("buyer@example.com", "구매자"));
        when(memberDirectory.findUserIdByEmail(anyString()))
                .thenAnswer(inv -> {
                    String email = inv.getArgument(0);
                    return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
                });
    }

    // ============================================================
    // §3.4 seller ship → ShippingStartedEvent 발행 종단 스모크
    // ============================================================

    @Test
    @DisplayName("§3.4 seller ship → ShippingStartedEvent 1건 AFTER_COMMIT 발행 (notification 종단 스모크)")
    void sellerShip_publishesShippingStartedEvent_smoke() {
        // given
        long sellerA = insertUser("smoke-seller-" + System.nanoTime() + "@test.com");
        long buyerId = insertBuyer("smoke-buyer-" + System.nanoTime() + "@test.com");
        String sellerAEmail = getEmail(sellerA);

        long variantId = insertVariantForSeller(sellerA, "스모크상품");
        long productId = getProductId(variantId);
        long orderId = insertPaidOrder(buyerId, sellerA, variantId, "스모크상품", 1, new BigDecimal("5000"));

        configProductMock(variantId, productId, "스모크상품", new BigDecimal("5000"));

        // seller 배송 생성
        var created = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        long shipmentId = created.shipmentId();

        // when: seller ship
        sellerFulfillmentFacade.ship(sellerAEmail, shipmentId, "CJ대한통운", "SMOKE-TRK-001");

        // then: ShippingStartedEvent AFTER_COMMIT 1건
        List<ShippingStartedEvent> events = captureListener.getEvents();
        assertThat(events).as("seller ship 후 ShippingStartedEvent 1건 발행").hasSize(1);

        ShippingStartedEvent event = events.get(0);
        assertThat(event.shipmentId()).isEqualTo(shipmentId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.carrier()).isEqualTo("CJ대한통운");
        assertThat(event.trackingNumber()).isEqualTo("SMOKE-TRK-001");
        assertThat(event.memberId()).isEqualTo(buyerId);
        assertThat(event.memberEmail()).isEqualTo("buyer@example.com");
        assertThat(event.items()).hasSize(1);

        // shipment status = shipping
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("shipping");

        // order status = shipping (rollup)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("shipping");
    }

    // ============================================================
    // 테스트 전용 이벤트 캡처 리스너
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<ShippingStartedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(ShippingStartedEvent event) {
            events.add(event);
        }

        public List<ShippingStartedEvent> getEvents() {
            return Collections.unmodifiableList(events);
        }

        public void clear() {
            events.clear();
        }
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "판매자", "SELLER", "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertBuyer(String email) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "구매자", "CONSUMER", "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private String getEmail(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
    }

    private long insertVariantForSeller(long sellerId, String productName) {
        String unique = productName + "-" + System.nanoTime();
        long catId = insertCategoryIfNeeded();
        jdbc.update("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)",
                sellerId, catId, unique, new BigDecimal("5000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, unique);
        String sku = "SKU-SMOKE-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock, is_active) VALUES(?,?,?,?,?)",
                productId, sku, new BigDecimal("5000"), 100, true);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long getProductId(long variantId) {
        return jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
    }

    private long insertPaidOrder(long buyerId, long sellerId, long variantId,
                                  String productName, int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-SMOKE-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", lineAmount, BigDecimal.ZERO, BigDecimal.ZERO, lineAmount,
                "수령인", "010-0000-0000", "12345", "서울시");
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items(order_id, variant_id, owner_id, product_name, " +
                    "unit_price, quantity, line_amount) VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, sellerId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private void configProductMock(long variantId, long productId, String productName, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, productName, null, List.of(),
                price, true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long categoryCache = -1L;

    private long insertCategoryIfNeeded() {
        if (categoryCache > 0) return categoryCache;
        String slug = "cat-smoke-" + System.nanoTime();
        jdbc.update("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)",
                "스모크테스트카테고리", slug, 1);
        categoryCache = jdbc.queryForObject("SELECT id FROM categories WHERE slug=?", Long.class, slug);
        return categoryCache;
    }
}
