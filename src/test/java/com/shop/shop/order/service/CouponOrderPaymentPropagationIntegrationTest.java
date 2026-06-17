package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.service.PaymentService;
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
 * 쿠폰 적용 주문 금액 전파 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증 (plan §5.4):
 * <ul>
 *   <li>쿠폰 적용 주문의 {@code Payment.amount == Order.finalAmount} (할인 반영)</li>
 *   <li>취소 시 환불액 == {@code Payment.amount}(할인 후 실 결제액)이며
 *       {@code itemsAmount}(판매가)와 다름 — 판매가 환불 회귀 차단</li>
 *   <li>discount > 0 케이스: {@code refundedAmount < itemsAmount} 명시 단언</li>
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
class CouponOrderPaymentPropagationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

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
    // Payment.amount == Order.finalAmount (할인 반영)
    // ============================================================

    @Test
    @DisplayName("쿠폰 할인 적용 주문 결제 → Payment.amount == Order.finalAmount (itemsAmount 아님)")
    void pay_withCouponDiscount_paymentAmountEqualsFinalAmount() {
        // given: itemsAmount=10000, discountAmount=2000, finalAmount=8000
        long userId = insertUser("prop-pay-1@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("PROP-PAY-1", 1000L, null);

        // 쿠폰 적용 주문 직접 삽입 (finalAmount = itemsAmount - discountAmount)
        BigDecimal itemsAmount = new BigDecimal("10000.00");
        BigDecimal discountAmount = new BigDecimal("2000.00");
        BigDecimal finalAmount = itemsAmount.subtract(discountAmount); // 8000.00
        long orderId = insertOrderWithDiscount(userId, variantId, "상품A", 1,
                new BigDecimal("10000"), itemsAmount, discountAmount, finalAmount);

        configProductMock(variantId, 1L, "상품A", new BigDecimal("10000"));

        // when — 결제 처리
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId,
                new PaymentService.PaymentCommand(null, null));

        // then
        assertThat(result.declined()).isFalse();

        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("paid");

        // Payment.amount == Order.finalAmount (할인 반영)
        assertThat(payment.getAmount()).isEqualByComparingTo(finalAmount);

        // Payment.amount != itemsAmount (판매가와 다름)
        assertThat(payment.getAmount()).isNotEqualByComparingTo(itemsAmount);

        // Payment.amount < itemsAmount (할인 후 실 결제액은 판매가 미만)
        assertThat(payment.getAmount().compareTo(itemsAmount)).isLessThan(0);
    }

    @Test
    @DisplayName("쿠폰 미적용 주문 결제 → Payment.amount == itemsAmount (discount=0)")
    void pay_noCoupon_paymentAmountEqualsItemsAmount() {
        // given: discount=0, finalAmount=itemsAmount
        long userId = insertUser("prop-pay-2@test.com");
        long variantId = insertVariant(10);
        BigDecimal itemsAmount = new BigDecimal("10000.00");
        long orderId = insertOrder(userId, variantId, "상품B", 1, new BigDecimal("10000"), itemsAmount);

        configProductMock(variantId, 2L, "상품B", new BigDecimal("10000"));

        // when
        PaymentService.PaymentResult result = paymentService.pay(userId, orderId,
                new PaymentService.PaymentCommand(null, null));

        // then
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("paid");
        assertThat(payment.getAmount()).isEqualByComparingTo(itemsAmount);
    }

    // ============================================================
    // 취소 시 환불액 == Payment.amount (할인 후 실 결제액)
    // ============================================================

    @Test
    @DisplayName("쿠폰 할인 주문 취소 → 환불액 == Payment.amount(finalAmount), itemsAmount 아님")
    void cancel_withCouponDiscount_refundAmountEqualsFinalAmountNotItemsAmount() {
        // given: itemsAmount=10000, discountAmount=3000, finalAmount=7000
        long userId = insertUser("prop-cancel-1@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("PROP-CANCEL-1", 1000L, null);

        BigDecimal itemsAmount = new BigDecimal("10000.00");
        BigDecimal discountAmount = new BigDecimal("3000.00");
        BigDecimal finalAmount = itemsAmount.subtract(discountAmount); // 7000.00
        long orderId = insertOrderWithDiscount(userId, variantId, "상품C", 1,
                new BigDecimal("10000"), itemsAmount, discountAmount, finalAmount);

        // 쿠폰 사용 처리 (user_coupon 연결)
        long ucId = insertUserCouponUsed(userId, couponId, orderId);
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        configProductMock(variantId, 3L, "상품C", new BigDecimal("10000"));

        // 결제 먼저 (paid 상태로)
        paymentService.pay(userId, orderId, new PaymentService.PaymentCommand(null, null));

        var paymentBefore = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(paymentBefore.getAmount()).isEqualByComparingTo(finalAmount); // 7000

        // when — 취소 (paid 상태이므로 환불 경로)
        PaymentService.CancelResult cancelResult = paymentService.cancel(userId, orderId);

        // then
        assertThat(cancelResult.isRefunded()).isTrue();

        // 환불액 == Payment.amount(finalAmount=7000) — 판매가(10000) 아님
        long expectedRefund = finalAmount.longValue(); // 7000
        assertThat(cancelResult.refundedAmount()).isEqualTo(expectedRefund);

        // 핵심 단언: 환불액 < itemsAmount (판매가 환불 회귀 차단)
        assertThat(cancelResult.refundedAmount()).isLessThan(itemsAmount.longValue());

        // payment.amount == refundedAmount (환불액 = 결제금액 일치)
        var paymentAfter = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(paymentAfter.getStatus()).isEqualTo("refunded");
        assertThat(paymentAfter.getAmount()).isEqualByComparingTo(
                BigDecimal.valueOf(cancelResult.refundedAmount()));
    }

    @Test
    @DisplayName("discount > 0 케이스: refundedAmount < itemsAmount 명시 단언 (판매가 환불 회귀 차단)")
    void cancel_discountApplied_refundIsLessThanItemsAmount() {
        // given: 명시 단언용 — discount=5000, itemsAmount=15000, finalAmount=10000
        long userId = insertUser("prop-cancel-2@test.com");
        long variantId = insertVariant(10);
        long couponId = insertCoupon("PROP-CANCEL-2", 5000L, null);

        BigDecimal itemsAmount = new BigDecimal("15000.00");
        BigDecimal discountAmount = new BigDecimal("5000.00");
        BigDecimal finalAmount = new BigDecimal("10000.00");
        long orderId = insertOrderWithDiscount(userId, variantId, "상품D", 1,
                new BigDecimal("15000"), itemsAmount, discountAmount, finalAmount);

        // 쿠폰 사용 처리
        insertUserCouponUsed(userId, couponId, orderId);
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        configProductMock(variantId, 4L, "상품D", new BigDecimal("15000"));

        // 결제
        paymentService.pay(userId, orderId, new PaymentService.PaymentCommand(null, null));

        // when
        PaymentService.CancelResult cancelResult = paymentService.cancel(userId, orderId);

        // then — 명시 단언
        assertThat(cancelResult.isRefunded()).isTrue();
        assertThat(cancelResult.refundedAmount()).isEqualTo(10000L); // finalAmount
        assertThat(cancelResult.refundedAmount()).isLessThan(15000L); // < itemsAmount (판매가)

        // discountAmount만큼 차감됨 검증
        long discount = itemsAmount.longValue() - cancelResult.refundedAmount();
        assertThat(discount).isEqualTo(discountAmount.longValue()); // 5000
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
        String sku = "PROP-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('전파테스트상품', '설명', 10000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 10000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertCoupon(String code, long value, Integer usageLimit) {
        Timestamp starts = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        if (usageLimit == null) {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, used_count, is_active) "
                    + "VALUES (?, '전파테스트쿠폰', 'fixed', ?, 0, ?, ?, 0, true)",
                    code, value, starts, ends);
        } else {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, usage_limit, used_count, is_active) "
                    + "VALUES (?, '전파테스트쿠폰', 'fixed', ?, 0, ?, ?, ?, 0, true)",
                    code, value, starts, ends, usageLimit);
        }
        return jdbc.queryForObject("SELECT id FROM coupons WHERE code=?", Long.class, code);
    }

    private long insertOrder(long userId, long variantId, String productName,
                             int quantity, BigDecimal unitPrice, BigDecimal itemsAmount) {
        String orderNumber = "ORD-PROP-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-0000-0000', '12345', '서울')",
                userId, orderNumber, itemsAmount, itemsAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private long insertOrderWithDiscount(long userId, long variantId, String productName,
                                         int quantity, BigDecimal unitPrice,
                                         BigDecimal itemsAmount, BigDecimal discountAmount,
                                         BigDecimal finalAmount) {
        String orderNumber = "ORD-PROP-DISC-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, ?, 0, ?, '수령인', '010-0000-0000', '12345', '서울')",
                userId, orderNumber, itemsAmount, discountAmount, finalAmount);
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
