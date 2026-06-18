package com.shop.shop.order.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.common.exception.CouponConflictException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 동시 사용 경합 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>동시 주문 2건이 같은 user_coupon 적용 →
 *       markUsedIfUnused 조건부 UPDATE로 1건만 성공, 패자는 영향행 0(→ CouponConflictException 트리거)</li>
 *   <li>usage_limit=N 한도에서 동시 N+1건 사용 →
 *       incrementUsedCountIfWithinLimit로 N건만 성공, used_count ≤ usage_limit 단언</li>
 * </ul>
 *
 * <p>참고: OrderCreationConcurrencyIntegrationTest 패턴(ExecutorService + CountDownLatch).
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
class CouponConcurrencyIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ============================================================
    // 1회용 user_coupon 동시 사용 — 1건만 성공
    // ============================================================

    @Test
    @DisplayName("같은 user_coupon 동시 2건 markUsedIfUnused → 1건만 성공(영향행 1), 나머지 영향행 0")
    void concurrentMarkUsed_sameCoupon_onlyOneSucceeds() throws Exception {
        // given
        long userId = insertUser("concurrency-1@test.com");
        long couponId = insertCoupon("CONC-UC-1", null);
        long orderId1 = insertPendingOrder(userId);
        long orderId2 = insertPendingOrder(userId);
        long ucId = insertUserCoupon(userId, couponId);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Instant now = Instant.now();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                Integer affected = txTemplate.execute(status ->
                        userCouponRepository.markUsedIfUnused(ucId, userId, orderId1, now));
                if (affected != null && affected == 1) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (CouponConflictException e) {
                failCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                Integer affected = txTemplate.execute(status ->
                        userCouponRepository.markUsedIfUnused(ucId, userId, orderId2, Instant.now()));
                if (affected != null && affected == 1) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (CouponConflictException e) {
                failCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 정확히 1건만 성공 (1회용 보장)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        // user_coupon used_at 기록됨
        Timestamp usedAtTs = jdbc.queryForObject(
                "SELECT used_at FROM user_coupons WHERE id=?", Timestamp.class, ucId);
        assertThat(usedAtTs).isNotNull();
    }

    // ============================================================
    // usage_limit=N 동시 N+1건 사용 — N건만 성공
    // ============================================================

    @Test
    @DisplayName("usage_limit=3 쿠폰에 동시 4건 incrementUsedCount → 3건만 성공, used_count ≤ 3")
    void concurrentIncrement_limitN_onlyNSucceed() throws Exception {
        // given: usage_limit=3
        int limit = 3;
        int threads = 4; // N+1
        long couponId = insertCouponWithLimit("CONC-LIMIT-1", limit);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        TransactionTemplate txTemplate2 = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    Integer affected = txTemplate2.execute(status ->
                            couponRepository.incrementUsedCountIfWithinLimit(couponId));
                    if (affected != null && affected == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 성공 ≤ limit, 총 시도 = limit + 1
        assertThat(successCount.get()).isLessThanOrEqualTo(limit);
        assertThat(successCount.get() + failCount.get()).isEqualTo(threads);

        // used_count ≤ usage_limit (음수 불가, 한도 초과 불가)
        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isLessThanOrEqualTo(limit);
        assertThat(usedCount).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("usage_limit=1 쿠폰 동시 2건 소비 → 1건만 성공, used_count=1")
    void concurrentIncrement_limit1_onlyOneSucceeds() throws Exception {
        // given: usage_limit=1
        long couponId = insertCouponWithLimit("CONC-LIMIT-2", 1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate3 = new TransactionTemplate(transactionManager);

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                Integer affected = txTemplate3.execute(status ->
                        couponRepository.incrementUsedCountIfWithinLimit(couponId));
                if (affected != null && affected == 1) successCount.incrementAndGet();
                else failCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                Integer affected = txTemplate3.execute(status ->
                        couponRepository.incrementUsedCountIfWithinLimit(couponId));
                if (affected != null && affected == 1) successCount.incrementAndGet();
                else failCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        int usedCount = jdbc.queryForObject(
                "SELECT used_count FROM coupons WHERE id=?", Integer.class, couponId);
        assertThat(usedCount).isEqualTo(1); // 한도 초과 없음
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

    private long insertCouponWithLimit(String code, int limit) {
        return insertCoupon(code, limit);
    }

    private long insertPendingOrder(long userId) {
        String orderNumber = "ORD-CONC-" + System.nanoTime() + "-" + Thread.currentThread().getId();
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
