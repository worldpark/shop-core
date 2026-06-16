package com.shop.shop.common.concurrency;

import com.shop.shop.common.config.RedisProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedissonSchedulerLeaderGuard 통합 테스트 (Testcontainers Redis + Redisson 클라이언트 2개).
 *
 * <p>PasswordResetRedisIntegrationTest(C7) 패턴 답습:
 * {@code GenericContainer("redis:7-alpine")} + {@code @DynamicPropertySource} 방식 대신
 * 순수 Redisson Config로 직접 클라이언트를 생성한다(Spring Boot 컨텍스트 불필요).
 *
 * <p>검증:
 * <ul>
 *   <li>(a) 동시 tryLock 정확히 1개 획득 — 두 클라이언트가 같은 lockKey에 tryLock(0) → 정확히 1개만 true</li>
 *   <li>(b) 해제 후 타 클라이언트 획득 — 리더가 unlock 후 타 클라이언트 tryLock(0) → true</li>
 *   <li>(c) 비대기 즉시 skip — 보유 중 타 클라이언트 tryLock(0)이 대기 없이 즉시 false</li>
 *   <li>(d) finally 가드 해제 결과 — 획득 실패 클라이언트가 unlock 안 해 보유자 락 유지 확인</li>
 * </ul>
 *
 * <p>watchdog 페일오버(리더 노드 사망 → ~30s 후 락 만료 → 타 노드 획득)는 타이밍 의존이라
 * 결정적 단언이 어렵다 → 통합/수동 한정(5.5). 이 테스트에서는 검증하지 않는다.
 */
@Testcontainers
class RedissonSchedulerLeaderGuardTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private RedissonClient clientA;
    private RedissonClient clientB;
    private RedisProperties redisProperties;

    private static final String RESOURCE = "scheduler:test-leader";
    private static final String LOCK_PREFIX = "shopcore:lock:test:"; // 테스트 격리 prefix

    @BeforeEach
    void setUp() {
        String redisAddress = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);

        Config configA = new Config();
        configA.useSingleServer().setAddress(redisAddress).setDatabase(0);
        clientA = Redisson.create(configA);

        Config configB = new Config();
        configB.useSingleServer().setAddress(redisAddress).setDatabase(0);
        clientB = Redisson.create(configB);

        // 테스트용 RedisProperties (lock prefix를 테스트 격리용으로 사용)
        redisProperties = new RedisProperties(null, new RedisProperties.Lock(LOCK_PREFIX, null));
    }

    @AfterEach
    void tearDown() {
        // 락 키 정리 (다음 테스트 격리)
        String lockKey = LOCK_PREFIX + RESOURCE;
        try {
            RLock lock = clientA.getLock(lockKey);
            if (lock.isLocked()) {
                lock.forceUnlock();
            }
        } catch (Exception ignored) {
            // 정리 실패 무시
        }

        if (clientA != null) clientA.shutdown();
        if (clientB != null) clientB.shutdown();
    }

    // ============================================================
    // (a) 동시 tryLock 정확히 1개 획득
    // ============================================================

    @Test
    @DisplayName("(a) 동시 tryLock — 정확히 1개 클라이언트만 획득, 나머지 false")
    void concurrent_tryLock_exactly_one_acquires() throws InterruptedException {
        // given — 두 가드(클라이언트 A, B)
        RedissonSchedulerLeaderGuard guardA = new RedissonSchedulerLeaderGuard(clientA, redisProperties);
        RedissonSchedulerLeaderGuard guardB = new RedissonSchedulerLeaderGuard(clientB, redisProperties);

        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // 두 스레드가 동시에 runIfLeader 시도
        Thread threadA = new Thread(() -> {
            try {
                startLatch.await();
                guardA.runIfLeader(RESOURCE, executionCount::incrementAndGet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        Thread threadB = new Thread(() -> {
            try {
                startLatch.await();
                guardB.runIfLeader(RESOURCE, executionCount::incrementAndGet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        threadA.start();
        threadB.start();

        // 동시 발화
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);

        // then — 정확히 1개 클라이언트만 task 실행
        assertThat(executionCount.get()).isEqualTo(1);
    }

    // ============================================================
    // (b) 해제 후 타 클라이언트 획득
    // ============================================================

    @Test
    @DisplayName("(b) 리더 락 해제 후 타 클라이언트가 획득할 수 있다")
    void after_release_other_client_can_acquire() throws InterruptedException {
        // given — guardA가 먼저 락 획득 후 해제
        RedissonSchedulerLeaderGuard guardA = new RedissonSchedulerLeaderGuard(clientA, redisProperties);
        RedissonSchedulerLeaderGuard guardB = new RedissonSchedulerLeaderGuard(clientB, redisProperties);

        AtomicInteger executionA = new AtomicInteger(0);
        AtomicInteger executionB = new AtomicInteger(0);

        // guardA 실행 (획득·해제)
        boolean leaderA = guardA.runIfLeader(RESOURCE, executionA::incrementAndGet);
        assertThat(leaderA).isTrue();
        assertThat(executionA.get()).isEqualTo(1);

        // guardA가 해제한 뒤 guardB가 획득
        boolean leaderB = guardB.runIfLeader(RESOURCE, executionB::incrementAndGet);

        // then — guardA 해제 후 guardB 획득 성공
        assertThat(leaderB).isTrue();
        assertThat(executionB.get()).isEqualTo(1);
    }

    // ============================================================
    // (c) 비대기 즉시 skip — 보유 중 타 클라이언트 false
    // ============================================================

    @Test
    @DisplayName("(c) 보유 중에 타 클라이언트 tryLock(0) → 대기 없이 즉시 false")
    void while_held_other_client_returns_false_immediately() throws InterruptedException {
        // given — clientA가 직접 락 보유
        String lockKey = LOCK_PREFIX + RESOURCE;
        RLock lockA = clientA.getLock(lockKey);
        lockA.lock(); // leaseTime 미지정 → watchdog

        try {
            // guardB가 비대기로 시도
            RedissonSchedulerLeaderGuard guardB = new RedissonSchedulerLeaderGuard(clientB, redisProperties);
            AtomicInteger executed = new AtomicInteger(0);

            long start = System.currentTimeMillis();
            boolean result = guardB.runIfLeader(RESOURCE, executed::incrementAndGet);
            long elapsed = System.currentTimeMillis() - start;

            // then — 즉시 false (대기 없음: waitTime=0)
            assertThat(result).isFalse();
            assertThat(executed.get()).isEqualTo(0);
            // 비대기 확인: 1초 미만
            assertThat(elapsed).isLessThan(1000);

        } finally {
            // 락 A 해제 (tearDown에서도 정리하지만 명시적으로)
            if (lockA.isHeldByCurrentThread()) {
                lockA.unlock();
            }
        }
    }

    // ============================================================
    // (d) finally 가드 해제 — 획득 실패 클라이언트가 보유자 락 유지 확인
    // ============================================================

    @Test
    @DisplayName("(d) 획득 실패 클라이언트는 unlock 안 해 보유자 락이 유지된다")
    void failed_client_does_not_release_holder_lock() throws InterruptedException {
        // given — clientA가 락 보유
        String lockKey = LOCK_PREFIX + RESOURCE;
        RLock lockA = clientA.getLock(lockKey);
        lockA.lock();

        try {
            // clientB가 획득 실패
            RedissonSchedulerLeaderGuard guardB = new RedissonSchedulerLeaderGuard(clientB, redisProperties);
            boolean result = guardB.runIfLeader(RESOURCE, () -> {});
            assertThat(result).isFalse();

            // then — clientA의 락이 여전히 유지됨 (clientB가 unlock하지 않았음)
            assertThat(lockA.isLocked()).isTrue();

        } finally {
            if (lockA.isHeldByCurrentThread()) {
                lockA.unlock();
            }
        }
    }

    // ============================================================
    // runIfLeader 단위: 리더면 task 실행·true / 비리더면 task 미실행·false
    // ============================================================

    @Test
    @DisplayName("runIfLeader: 리더 노드면 task 실행 + true 반환")
    void runIfLeader_leader_executes_task_and_returns_true() {
        // given
        RedissonSchedulerLeaderGuard guard = new RedissonSchedulerLeaderGuard(clientA, redisProperties);
        AtomicInteger count = new AtomicInteger(0);

        // when
        boolean result = guard.runIfLeader(RESOURCE, count::incrementAndGet);

        // then
        assertThat(result).isTrue();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("runIfLeader: 비리더 노드면 task 미실행 + false 반환")
    void runIfLeader_non_leader_does_not_execute_task_and_returns_false() throws InterruptedException {
        // given — clientA가 락 보유
        String lockKey = LOCK_PREFIX + RESOURCE;
        RLock lockA = clientA.getLock(lockKey);
        lockA.lock();

        try {
            RedissonSchedulerLeaderGuard guardB = new RedissonSchedulerLeaderGuard(clientB, redisProperties);
            AtomicInteger count = new AtomicInteger(0);

            // when
            boolean result = guardB.runIfLeader(RESOURCE, count::incrementAndGet);

            // then
            assertThat(result).isFalse();
            assertThat(count.get()).isEqualTo(0);

        } finally {
            if (lockA.isHeldByCurrentThread()) {
                lockA.unlock();
            }
        }
    }
}
