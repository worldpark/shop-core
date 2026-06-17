package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 쿠폰 적용 주문 취소/만료 → 쿠폰 복원 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>쿠폰 적용 주문 취소(cancel) → restoreByOrder로 복원 (used_at NULL, used_count 감소)</li>
 *   <li>쿠폰 적용 주문 만료(cancelByExpiry) → 동일 복원 경로</li>
 *   <li>복원 멱등: 취소 후 재취소 → ALREADY_CANCELLED, 쿠폰 중복 복원 없음</li>
 *   <li>쿠폰 미적용 주문 취소 → 복원 no-op (used_count 변화 없음)</li>
 * </ul>
 *
 * <p>외부 의존: MemberDirectory, ProductOrderCatalog @MockitoBean.
 * Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class CouponRestoreOnCancelIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // 취소(cancel) → 쿠폰 복원
    // ============================================================

    @Test
    @DisplayName("쿠폰 적용 주문 cancel → used_at NULL, used_count 감소")
    void cancel_withCoupon_restoresCoupon() {
        // given
        long userId = insertUser("restore-cancel-1@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("RESTORE-CANCEL-1", null);
        long orderId = insertPendingOrderWithDiscount(userId, variantId, "상품A", 1,
                BigDecimal.valueOf(10000), BigDecimal.valueOf(1000));
        long ucId = insertUserCouponUsed(userId, couponId, orderId);

        // coupon used_count = 1 (사용 중)
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        configProductMock(variantId, 1L, "상품A", BigDecimal.valueOf(10000));

        // when
        OrderCancellation.OrderCancellationResult result =
                orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);

        // user_coupon 복원 확인
        Timestamp usedAtTs = jdbc.queryForObject(
                "SELECT used_at FROM user_coupons WHERE id=?", Timestamp.class, ucId);
        Long linkedOrderId = jdbc.queryForObject(
                "SELECT order_id FROM user_coupons WHERE id=?", Long.class, ucId);

        assertThat(usedAtTs).isNull();
        assertThat(linkedOrderId).isNull();

        // coupon used_count 감소
        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(0);
    }

    // ============================================================
    // 만료(cancelByExpiry) → 쿠폰 복원
    // ============================================================

    @Test
    @DisplayName("쿠폰 적용 주문 cancelByExpiry → used_at NULL, used_count 감소 (동일 복원 경로)")
    void cancelByExpiry_withCoupon_restoresCoupon() {
        // given
        long userId = insertUser("restore-expiry-1@test.com");
        long variantId = insertVariant(5);
        long couponId = insertCoupon("RESTORE-EXPIRY-1", null);
        long orderId = insertPendingOrderWithDiscount(userId, variantId, "상품B", 2,
                BigDecimal.valueOf(5000), BigDecimal.valueOf(500));
        long ucId = insertUserCouponUsed(userId, couponId, orderId);

        // coupon used_count = 1
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        configProductMock(variantId, 2L, "상품B", BigDecimal.valueOf(5000));

        // when — 만료 경로
        OrderCancellation.OrderCancellationResult result =
                orderCancellation.cancelByExpiry(orderId);

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);

        Timestamp usedAtTs = jdbc.queryForObject(
                "SELECT used_at FROM user_coupons WHERE id=?", Timestamp.class, ucId);
        assertThat(usedAtTs).isNull();

        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(0);
    }

    // ============================================================
    // 복원 멱등 — 재취소
    // ============================================================

    @Test
    @DisplayName("쿠폰 복원 후 재취소 → ALREADY_CANCELLED, 쿠폰 중복 복원 없음 (used_count ≥ 0)")
    void cancel_alreadyCancelled_idempotentNoCouponDoubleRestore() {
        // given
        long userId = insertUser("restore-idem-1@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("RESTORE-IDEM-1", null);
        long orderId = insertPendingOrderWithDiscount(userId, variantId, "상품C", 1,
                BigDecimal.valueOf(8000), BigDecimal.valueOf(800));
        long ucId = insertUserCouponUsed(userId, couponId, orderId);
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        configProductMock(variantId, 3L, "상품C", BigDecimal.valueOf(8000));

        // 첫 취소
        orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // 복원 확인
        int usedCountAfterFirst = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountAfterFirst).isEqualTo(0);

        // when — 멱등 재취소
        OrderCancellation.OrderCancellationResult result =
                orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then — ALREADY_CANCELLED, used_count 음수 불가
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.ALREADY_CANCELLED);

        int usedCountFinal = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountFinal).isGreaterThanOrEqualTo(0);
        assertThat(usedCountFinal).isEqualTo(0);
    }

    // ============================================================
    // 쿠폰 미적용 주문 취소 — 복원 no-op
    // ============================================================

    @Test
    @DisplayName("쿠폰 미적용 주문 cancel → coupon used_count 변화 없음 (no-op)")
    void cancel_noCoupon_noRestoreEffect() {
        // given — user_coupon 없음
        long userId = insertUser("restore-noop-1@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("RESTORE-NOOP-1", null);
        // coupon used_count=2 (다른 주문에서 사용된 상태라 가정)
        jdbc.update("UPDATE coupons SET used_count=2 WHERE id=?", couponId);

        long orderId = insertPendingOrder(userId, variantId, "상품D", 1, BigDecimal.valueOf(5000));
        // user_coupon 연결 없음 (이 주문에 쿠폰 미적용)

        configProductMock(variantId, 4L, "상품D", BigDecimal.valueOf(5000));

        // when
        OrderCancellation.OrderCancellationResult result =
                orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then — 취소 성공, coupon used_count 변화 없음
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);

        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(2); // 변화 없음
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, long productId, String name, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, name, null, List.of(),
                price, true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트유저', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariant(int stock) {
        String sku = "RESTORE-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('복원테스트상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertCoupon(String code, Integer usageLimit) {
        Timestamp starts = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        if (usageLimit == null) {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, used_count, is_active) "
                    + "VALUES (?, '복원테스트쿠폰', 'fixed', 1000, 0, ?, ?, 0, true)",
                    code, starts, ends);
        } else {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, usage_limit, used_count, is_active) "
                    + "VALUES (?, '복원테스트쿠폰', 'fixed', 1000, 0, ?, ?, ?, 0, true)",
                    code, starts, ends, usageLimit);
        }
        return jdbc.queryForObject("SELECT id FROM coupons WHERE code=?", Long.class, code);
    }

    private long insertPendingOrder(long userId, long variantId, String productName,
                                    int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-RESTORE-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-0000-0000', '12345', '서울')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private long insertPendingOrderWithDiscount(long userId, long variantId, String productName,
                                                int quantity, BigDecimal unitPrice,
                                                BigDecimal discountAmount) {
        String orderNumber = "ORD-RESTORE-DISC-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal finalAmount = lineAmount.subtract(discountAmount);
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, ?, 0, ?, '수령인', '010-0000-0000', '12345', '서울')",
                userId, orderNumber, lineAmount, discountAmount, finalAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private long insertUserCouponUsed(long userId, long couponId, long orderId) {
        Timestamp usedAt = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, order_id, issued_at, used_at) "
                + "VALUES (?, ?, ?, ?, ?)",
                userId, couponId, orderId, usedAt, usedAt);
        return jdbc.queryForObject(
                "SELECT id FROM user_coupons WHERE user_id=? AND coupon_id=? ORDER BY id DESC LIMIT 1",
                Long.class, userId, couponId);
    }
}
