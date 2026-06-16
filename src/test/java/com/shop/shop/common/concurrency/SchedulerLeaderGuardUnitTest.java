package com.shop.shop.common.concurrency;

import com.shop.shop.common.config.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RedissonSchedulerLeaderGuard 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>tryLock(0, TimeUnit.SECONDS) — 2-인자 오버로드(leaseTime 미지정, watchdog)</li>
 *   <li>획득 성공 시 task 실행 + true 반환 + isHeldByCurrentThread=true 시 unlock 정확히 1회</li>
 *   <li>획득 실패 시 task 미실행 + false 반환 + unlock 0회</li>
 *   <li>isHeldByCurrentThread=false 시 unlock 0회(watchdog 만료 후 타 노드 재획득 방어)</li>
 *   <li>InterruptedException — 인터럽트 복원 + task 미실행 + false 반환</li>
 *   <li>Redis 연결 실패(RedisException) — 폴백 skip + false 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SchedulerLeaderGuardUnitTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private RedisProperties redisProperties;
    private RedissonSchedulerLeaderGuard guard;

    private static final String RESOURCE = "scheduler:test-resource";
    private static final String LOCK_PREFIX = "shopcore:lock:";
    private static final String EXPECTED_LOCK_KEY = LOCK_PREFIX + RESOURCE;

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties(null, null); // 기본값 폴백 사용
        guard = new RedissonSchedulerLeaderGuard(redissonClient, redisProperties);
        when(redissonClient.getLock(EXPECTED_LOCK_KEY)).thenReturn(lock);
    }

    // ============================================================
    // leaseTime 미지정 오버로드 단언 — Task 035 기술제약 3
    // ============================================================

    @Test
    @DisplayName("tryLock(0, TimeUnit.SECONDS) — 2-인자 오버로드(leaseTime 미지정·watchdog) 호출 단언")
    void runIfLeader_calls_tryLock_two_arg_no_leaseTime() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then — 2-인자 오버로드(leaseTime 없음) 호출 확인
        verify(lock).tryLock(0, TimeUnit.SECONDS);
    }

    // ============================================================
    // 획득 성공 — task 실행 + true + unlock 1회
    // ============================================================

    @Test
    @DisplayName("획득 성공 시 task 실행 + true 반환")
    void runIfLeader_acquired_executes_task_and_returns_true() throws InterruptedException {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // when
        boolean result = guard.runIfLeader(RESOURCE, () -> executed.set(true));

        // then
        assertThat(result).isTrue();
        assertThat(executed.get()).isTrue();
    }

    @Test
    @DisplayName("획득 성공 + isHeldByCurrentThread=true → unlock 정확히 1회 (finally 가드 해제)")
    void runIfLeader_acquired_isHeld_true_unlocks_exactly_once() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then — unlock 정확히 1회 (isHeldByCurrentThread=true 분기)
        verify(lock, times(1)).unlock();
    }

    @Test
    @DisplayName("획득 성공 + isHeldByCurrentThread=false → unlock 0회 (watchdog 만료 후 타 노드 재획득 방어)")
    void runIfLeader_acquired_isHeld_false_does_not_unlock() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false); // watchdog 만료 후 타 노드가 재획득한 상황

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then — unlock 호출 안 됨
        verify(lock, never()).unlock();
    }

    // ============================================================
    // 획득 실패 — task 미실행 + false + unlock 0회
    // ============================================================

    @Test
    @DisplayName("획득 실패 시 task 미실행 + false 반환")
    void runIfLeader_not_acquired_does_not_execute_task_and_returns_false() throws InterruptedException {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        // when
        boolean result = guard.runIfLeader(RESOURCE, () -> executed.set(true));

        // then
        assertThat(result).isFalse();
        assertThat(executed.get()).isFalse();
    }

    @Test
    @DisplayName("획득 실패 시 unlock 0회 (타 노드 락 침범 방지)")
    void runIfLeader_not_acquired_never_unlocks() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then — unlock 호출 없음
        verify(lock, never()).unlock();
    }

    // ============================================================
    // InterruptedException — 인터럽트 복원 + task 미실행 + false
    // ============================================================

    @Test
    @DisplayName("InterruptedException 발생 시 인터럽트 복원 + task 미실행 + false 반환")
    void runIfLeader_interrupted_restores_interrupt_and_returns_false() throws InterruptedException {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException("테스트 인터럽트"));

        // when
        boolean result = guard.runIfLeader(RESOURCE, () -> executed.set(true));

        // then
        assertThat(result).isFalse();
        assertThat(executed.get()).isFalse();
        // 인터럽트 상태 복원 확인
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        // teardown — 다음 테스트에 인터럽트 상태 오염 방지
        Thread.interrupted();
    }

    @Test
    @DisplayName("InterruptedException 발생 시 unlock 0회")
    void runIfLeader_interrupted_never_unlocks() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException("테스트 인터럽트"));

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then
        verify(lock, never()).unlock();

        // teardown
        Thread.interrupted();
    }

    // ============================================================
    // Redis 연결 실패(RedisException) — 폴백 skip + false
    // ============================================================

    @Test
    @DisplayName("Redis 연결 실패(RedisException) 시 폴백 skip + false 반환 + task 미실행")
    void runIfLeader_redis_exception_returns_false_and_skips_task() throws InterruptedException {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new RedisException("연결 실패"));

        // when
        boolean result = guard.runIfLeader(RESOURCE, () -> executed.set(true));

        // then
        assertThat(result).isFalse();
        assertThat(executed.get()).isFalse();
    }

    @Test
    @DisplayName("Redis 연결 실패 시 unlock 0회")
    void runIfLeader_redis_exception_never_unlocks() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new RedisException("연결 실패"));

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then
        verify(lock, never()).unlock();
    }

    // ============================================================
    // 락 키 조립 단언
    // ============================================================

    @Test
    @DisplayName("lockKey = prefix + resource 조립 단언")
    void runIfLeader_uses_correct_lock_key() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        // when
        guard.runIfLeader(RESOURCE, () -> {});

        // then — getLock이 올바른 키로 호출됐는지 확인
        verify(redissonClient).getLock(EXPECTED_LOCK_KEY);
    }

    // ============================================================
    // task 내부 예외 전파 + finally unlock 보장
    // ============================================================

    @Test
    @DisplayName("task 내부 예외 발생 시 finally에서 isHeldByCurrentThread=true면 unlock 후 예외 전파")
    void runIfLeader_task_throws_finally_unlocks_and_propagates() throws InterruptedException {
        // given
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // when & then
        try {
            guard.runIfLeader(RESOURCE, () -> { throw new RuntimeException("task 내부 예외"); });
        } catch (RuntimeException e) {
            // task 예외는 guard 밖으로 전파됨
            assertThat(e.getMessage()).isEqualTo("task 내부 예외");
        }

        // finally에서 unlock 호출 확인 (락 누수 방지)
        verify(lock, times(1)).unlock();
    }
}
