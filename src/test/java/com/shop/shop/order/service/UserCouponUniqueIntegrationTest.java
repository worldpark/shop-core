package com.shop.shop.order.service;

import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user_coupons UNIQUE(user_id, coupon_id) 제약 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>같은 (user_id, coupon_id) 2회 INSERT → 2번째 DataIntegrityViolationException(1인 1매)</li>
 *   <li>서로 다른 사용자 동일 쿠폰 → 각각 독립 발급 성공</li>
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
class UserCouponUniqueIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 (user_id, coupon_id) 2회 INSERT → 2번째 DataIntegrityViolationException (1인 1매)")
    void duplicateUserCoupon_throwsDataIntegrityViolation() {
        // given
        long userId = insertUser("unique-coupon-1@test.com");
        long couponId = insertCoupon("UNIQUE-TEST-1");

        // 첫 번째 발급 — 성공
        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                userId, couponId, Timestamp.from(Instant.now()));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id=? AND coupon_id=?",
                Integer.class, userId, couponId);
        assertThat(count).isEqualTo(1);

        // when — 같은 (user_id, coupon_id) 중복 INSERT
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                        userId, couponId, Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // then — user_coupons 행 1개만 존재
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id=? AND coupon_id=?",
                Integer.class, userId, couponId);
        assertThat(finalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 사용자 동일 쿠폰 → 각각 독립 발급 성공 (UNIQUE는 (user_id, coupon_id) 복합)")
    void differentUsers_sameCoupon_eachSucceeds() {
        // given
        long userId1 = insertUser("unique-coupon-2a@test.com");
        long userId2 = insertUser("unique-coupon-2b@test.com");
        long couponId = insertCoupon("UNIQUE-TEST-2");

        // when — 각 사용자별 발급
        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                userId1, couponId, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                userId2, couponId, Timestamp.from(Instant.now()));

        // then — 각 행 정상 존재
        Integer count1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id=? AND coupon_id=?",
                Integer.class, userId1, couponId);
        Integer count2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id=? AND coupon_id=?",
                Integer.class, userId2, couponId);

        assertThat(count1).isEqualTo(1);
        assertThat(count2).isEqualTo(1);
    }

    @Test
    @DisplayName("CouponService.claim 2회 호출 → 2번째 CouponAlreadyOwnedException(409)")
    void claimTwice_throwsCouponAlreadyOwnedException() {
        // given
        long userId = insertUser("unique-coupon-3@test.com");
        String code = "UNIQUE-CLAIM-1";
        insertCoupon(code);

        // 1회 발급 성공
        couponRepository.findByCode(code).ifPresent(coupon -> {
            jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                    userId, coupon.getId(), Timestamp.from(Instant.now()));
        });

        // when/then — 2번째 claim → DataIntegrityViolation (UNIQUE 위반)
        couponRepository.findByCode(code).ifPresent(coupon ->
                assertThatThrownBy(() ->
                        jdbc.update("INSERT INTO user_coupons (user_id, coupon_id, issued_at) VALUES (?, ?, ?)",
                                userId, coupon.getId(), Timestamp.from(Instant.now())))
                        .isInstanceOf(DataIntegrityViolationException.class)
        );
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트유저', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertCoupon(String code) {
        Timestamp starts = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp ends = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO coupons (code, name, discount_type, value, min_order_amount, "
                + "starts_at, ends_at, used_count, is_active) "
                + "VALUES (?, '테스트쿠폰', 'fixed', ?, 0, ?, ?, 0, true)",
                code, BigDecimal.valueOf(1000), starts, ends);
        return jdbc.queryForObject("SELECT id FROM coupons WHERE code=?", Long.class, code);
    }
}
