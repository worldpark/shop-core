package com.shop.shop.order.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
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

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 소비(consume)/복원(restore) 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>markUsedIfUnused → used_at/order_id 기록, used_count 미변경</li>
 *   <li>incrementUsedCountIfWithinLimit → used_count++</li>
 *   <li>restoreByOrderId → used_at/order_id NULL 복원</li>
 *   <li>decrementUsedCount → used_count--</li>
 *   <li>복원 멱등 재호출 → 영향행 0, used_count 음수 불가</li>
 *   <li>usage_limit 한도 초과 → incrementUsedCountIfWithinLimit 영향행 0</li>
 * </ul>
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
class CouponConsumeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EnvelopeEncryptionService crypto;

    // ============================================================
    // markUsedIfUnused
    // ============================================================

    @Test
    @Transactional
    @DisplayName("markUsedIfUnused — used_at/order_id 기록, 영향행 1 반환")
    void markUsedIfUnused_unused_recordsUsedAtAndOrderId() {
        // given
        long userId = insertUser("consume-mark-1@test.com");
        long couponId = insertCoupon("CONSUME-MARK-1", null);
        long orderId = insertPendingOrder(userId);
        long ucId = insertUserCoupon(userId, couponId);
        Instant now = Instant.now();

        // when
        int affected = userCouponRepository.markUsedIfUnused(ucId, userId, orderId, now);

        // then
        assertThat(affected).isEqualTo(1);

        Timestamp usedAtTs = jdbc.queryForObject(
                "SELECT used_at FROM user_coupons WHERE id=?", Timestamp.class, ucId);
        Long linkedOrderId = jdbc.queryForObject(
                "SELECT order_id FROM user_coupons WHERE id=?", Long.class, ucId);

        assertThat(usedAtTs).isNotNull();
        assertThat(linkedOrderId).isEqualTo(orderId);
    }

    @Test
    @Transactional
    @DisplayName("markUsedIfUnused — 이미 사용된 쿠폰 → 영향행 0 (1회용 보장)")
    void markUsedIfUnused_alreadyUsed_returns0() {
        // given
        long userId = insertUser("consume-mark-2@test.com");
        long couponId = insertCoupon("CONSUME-MARK-2", null);
        long orderId1 = insertPendingOrder(userId);
        long orderId2 = insertPendingOrder(userId);
        long ucId = insertUserCoupon(userId, couponId);
        Instant now = Instant.now();

        // 1차 사용
        userCouponRepository.markUsedIfUnused(ucId, userId, orderId1, now);

        // when — 2차 사용 시도
        int affected = userCouponRepository.markUsedIfUnused(ucId, userId, orderId2, Instant.now());

        // then
        assertThat(affected).isEqualTo(0);

        // order_id는 1차 값 유지
        Long linkedOrderId = jdbc.queryForObject(
                "SELECT order_id FROM user_coupons WHERE id=?", Long.class, ucId);
        assertThat(linkedOrderId).isEqualTo(orderId1);
    }

    // ============================================================
    // incrementUsedCountIfWithinLimit
    // ============================================================

    @Test
    @Transactional
    @DisplayName("incrementUsedCountIfWithinLimit — used_count++ (usage_limit null → 무제한)")
    void incrementUsedCount_noLimit_increments() {
        // given
        long couponId = insertCoupon("INCREMENT-1", null); // usage_limit=null

        // when
        int affected = couponRepository.incrementUsedCountIfWithinLimit(couponId);

        // then
        assertThat(affected).isEqualTo(1);

        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("incrementUsedCountIfWithinLimit — usage_limit 초과 → 영향행 0 (한도 소진)")
    void incrementUsedCount_limitExceeded_returns0() {
        // given: usage_limit=1, used_count=1 (이미 소진)
        long couponId = insertCouponWithLimit("LIMIT-1", 1);
        jdbc.update("UPDATE coupons SET used_count=1 WHERE id=?", couponId);

        // when
        int affected = couponRepository.incrementUsedCountIfWithinLimit(couponId);

        // then
        assertThat(affected).isEqualTo(0);

        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(1); // 변경 없음
    }

    @Test
    @Transactional
    @DisplayName("incrementUsedCountIfWithinLimit — 비활성 쿠폰 → 영향행 0")
    void incrementUsedCount_inactive_returns0() {
        // given
        long couponId = insertInactiveCoupon("INACTIVE-INCREMENT-1");

        // when
        int affected = couponRepository.incrementUsedCountIfWithinLimit(couponId);

        // then
        assertThat(affected).isEqualTo(0);
    }

    // ============================================================
    // restoreByOrderId + decrementUsedCount
    // ============================================================

    @Test
    @Transactional
    @DisplayName("restoreByOrderId — used_at/order_id NULL 복원, 영향행 1")
    void restoreByOrderId_used_restoresSuccessfully() {
        // given
        long userId = insertUser("restore-1@test.com");
        long couponId = insertCoupon("RESTORE-1", null);
        long orderId = insertPendingOrder(userId);
        long ucId = insertUserCoupon(userId, couponId);
        Instant now = Instant.now();

        // 사용 처리
        userCouponRepository.markUsedIfUnused(ucId, userId, orderId, now);
        couponRepository.incrementUsedCountIfWithinLimit(couponId);

        // used_count = 1 확인
        int usedCountBefore = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountBefore).isEqualTo(1);

        // when — 복원
        int restored = userCouponRepository.restoreByOrderId(orderId);
        int decremented = couponRepository.decrementUsedCount(couponId);

        // then
        assertThat(restored).isEqualTo(1);
        assertThat(decremented).isEqualTo(1);

        Timestamp usedAtTs = jdbc.queryForObject(
                "SELECT used_at FROM user_coupons WHERE id=?", Timestamp.class, ucId);
        Long linkedOrderId = jdbc.queryForObject(
                "SELECT order_id FROM user_coupons WHERE id=?", Long.class, ucId);

        assertThat(usedAtTs).isNull();
        assertThat(linkedOrderId).isNull();

        int usedCountAfter = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountAfter).isEqualTo(0);
    }

    @Test
    @Transactional
    @DisplayName("복원 멱등 재호출 — 이미 복원된 user_coupon → 영향행 0, used_count 음수 불가")
    void restoreByOrderId_alreadyRestored_idempotentNoDoubleDecrement() {
        // given
        long userId = insertUser("restore-2@test.com");
        long couponId = insertCoupon("RESTORE-2", null);
        long orderId = insertPendingOrder(userId);
        long ucId = insertUserCoupon(userId, couponId);
        Instant now = Instant.now();

        // 사용 후 복원
        userCouponRepository.markUsedIfUnused(ucId, userId, orderId, now);
        couponRepository.incrementUsedCountIfWithinLimit(couponId);
        userCouponRepository.restoreByOrderId(orderId);
        couponRepository.decrementUsedCount(couponId);

        // used_count=0 확인
        int usedCountAfterFirst = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountAfterFirst).isEqualTo(0);

        // when — 멱등 재호출
        int restored2 = userCouponRepository.restoreByOrderId(orderId);
        int decremented2 = couponRepository.decrementUsedCount(couponId);

        // then — 영향행 0, used_count 음수 불가 (0 유지)
        assertThat(restored2).isEqualTo(0);
        assertThat(decremented2).isEqualTo(0);

        int usedCountFinal = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCountFinal).isEqualTo(0); // 음수 불가
    }

    @Test
    @Transactional
    @DisplayName("쿠폰 미적용 주문 restoreByOrderId → 영향행 0 (no-op, 멱등)")
    void restoreByOrderId_noCouponApplied_returnsZero() {
        // given — order_id에 연결된 user_coupon 없음
        long userId = insertUser("restore-3@test.com");
        long orderId = insertPendingOrder(userId);

        // when
        int restored = userCouponRepository.restoreByOrderId(orderId);

        // then
        assertThat(restored).isEqualTo(0);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트유저', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertCoupon(String code, Integer usageLimit) {
        Timestamp starts = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        if (usageLimit == null) {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, used_count, is_active) "
                    + "VALUES (?, '테스트쿠폰', 'fixed', 1000, 0, ?, ?, 0, true)",
                    code, starts, ends);
        } else {
            jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                    + "starts_at, ends_at, usage_limit, used_count, is_active) "
                    + "VALUES (?, '테스트쿠폰', 'fixed', 1000, 0, ?, ?, ?, 0, true)",
                    code, starts, ends, usageLimit);
        }
        return jdbc.queryForObject("SELECT id FROM coupons WHERE code=?", Long.class, code);
    }

    private long insertCouponWithLimit(String code, int usageLimit) {
        return insertCoupon(code, usageLimit);
    }

    private long insertInactiveCoupon(String code) {
        Timestamp starts = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                + "starts_at, ends_at, used_count, is_active) "
                + "VALUES (?, '비활성쿠폰', 'fixed', 1000, 0, ?, ?, 0, false)",
                code, starts, ends);
        return jdbc.queryForObject("SELECT id FROM coupons WHERE code=?", Long.class, code);
    }

    private long insertPendingOrder(long userId) {
        String orderNumber = "ORD-CONSUME-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', 10000, 0, 0, 10000, ?, ?, ?, ?)",
                userId, orderNumber,
                crypto.encrypt("수령인"), crypto.encrypt("010-0000-0000"),
                crypto.encrypt("12345"), crypto.encrypt("서울"));
        return jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    private long insertUserCoupon(long userId, long couponId) {
        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                userId, couponId, Timestamp.from(Instant.now()));
        return jdbc.queryForObject(
                "SELECT id FROM user_coupons WHERE user_id=? AND coupon_id=? ORDER BY id DESC LIMIT 1",
                Long.class, userId, couponId);
    }
}
