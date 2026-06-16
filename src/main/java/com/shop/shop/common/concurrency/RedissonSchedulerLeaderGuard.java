package com.shop.shop.common.concurrency;

import com.shop.shop.common.config.RedisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redisson {@link RLock} 기반 {@link SchedulerLeaderGuard} 구현체.
 *
 * <p><b>락 키 조립</b>: {@code RedisProperties.lock().prefix() + resource}<br>
 * 예: {@code shopcore:lock:scheduler:unpaid-order-expiry}
 *
 * <p><b>tryLock(0, SECONDS) 비대기</b><br>
 * waitTime=0으로 즉시 반환한다. 타 노드가 이미 리더이면 즉시 skip — 대기하지 않음.
 *
 * <p><b>leaseTime 미지정(2-인자 오버로드 — watchdog)</b><br>
 * {@code lock.tryLock(0, TimeUnit.SECONDS)} — leaseTime 인자 없는 오버로드를 사용한다.
 * Redisson watchdog이 보유 프로세스 생존 동안 락을 자동 갱신(기본 30s lease, 10s마다 갱신).
 * 고정 leaseTime(3-인자 오버로드) 사용 금지: 작업 overrun 시 실행 중 만료 → 중복 실행 재오픈(Task 035 기술제약 3).
 *
 * <p><b>finally 가드 해제</b><br>
 * {@code acquired && lock.isHeldByCurrentThread()}일 때만 {@code unlock()}.
 * (a) 획득 실패 시 unlock하지 않아 타 노드 락 침범 방지,
 * (b) watchdog lease 만료 후 타 노드가 재획득한 상태에서 잘못 unlock하는 것 방지.
 *
 * <p><b>Redis 연결 실패 폴백</b><br>
 * 락 인프라 장애 시 이번 주기 skip(false 반환). 다음 주기 재시도.
 * 정합은 도메인 행 락=멱등이 최종 방어선(ADR-005).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonSchedulerLeaderGuard implements SchedulerLeaderGuard {

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    /**
     * {@inheritDoc}
     *
     * <p><b>구현 흐름</b>:
     * <ol>
     *   <li>lockKey = {@code prefix + resource} 조립</li>
     *   <li>{@code RLock.tryLock(0, TimeUnit.SECONDS)} — 비대기·leaseTime 미지정(watchdog)</li>
     *   <li>획득 실패 → debug 로깅 + false 반환(task 미실행)</li>
     *   <li>획득 성공 → {@code task.run()} 실행 → true 반환</li>
     *   <li>finally → {@code acquired && isHeldByCurrentThread()} 시에만 unlock</li>
     * </ol>
     */
    @Override
    public boolean runIfLeader(String resource, Runnable task) {
        String lockKey = redisProperties.lock().prefix() + resource;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            // 비대기(waitTime=0) + leaseTime 미지정(2-인자 오버로드) → watchdog 자동 갱신
            acquired = lock.tryLock(0, TimeUnit.SECONDS);

            if (!acquired) {
                log.debug("분산락 획득 실패 — 타 노드가 리더. skip. resource={}, lockKey={}", resource, lockKey);
                return false;
            }

            log.debug("분산락 획득 성공 — 리더 실행. resource={}, lockKey={}", resource, lockKey);
            task.run();
            return true;

        } catch (InterruptedException e) {
            // 인터럽트 상태 복원 (비대기라 waitTime=0이지만 규약상 반드시 복원)
            Thread.currentThread().interrupt();
            log.warn("분산락 tryLock 중 인터럽트 발생 — 작업 미실행, skip. resource={}", resource, e);
            return false;

        } catch (Exception e) {
            // Redis 연결 실패(RedisException 등) → 폴백 skip (4절 정책: 이번 주기 skip + 다음 주기 재시도)
            log.warn("분산락 획득 중 예외 발생 — Redis 장애 추정, 이번 주기 skip. resource={}", resource, e);
            return false;

        } finally {
            // isHeldByCurrentThread 가드: 획득 실패 또는 watchdog 만료 후 타 노드 재획득 시 unlock 방지
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("분산락 해제. resource={}, lockKey={}", resource, lockKey);
            }
        }
    }
}
