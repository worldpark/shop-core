package com.shop.shop.common.concurrency;

/**
 * 스케줄러 다중 노드 중복 실행 축소용 best-effort 리더 게이트.
 *
 * <p><b>목적</b><br>
 * {@code @Scheduled} 배치가 다중 노드에서 동시에 발화할 때 한 노드만 실행하도록 게이트해
 * 중복 작업을 축소한다. strict 단일 실행 보장이 아니라 best-effort(정합은 도메인 행 락=멱등이 보장).
 *
 * <p><b>구현 규약 (구현체가 반드시 준수)</b>
 * <ul>
 *   <li>비대기: {@code tryLock(0, ...)} — 획득 실패 즉시 skip(대기 없음)</li>
 *   <li>leaseTime 미지정(watchdog 자동 갱신): 작업 overrun 시에도 실행 중 락 만료·중복 실행 재오픈 방지</li>
 *   <li>finally에서 {@code isHeldByCurrentThread()}일 때만 unlock: 미보유 시 타 노드 락 침범 방지</li>
 *   <li>{@link InterruptedException}: {@code Thread.currentThread().interrupt()} 복원 후 false 반환</li>
 *   <li>Redis 연결 실패: warn 로깅 + false 반환(폴백 skip — 락 인프라 장애가 흐름을 막지 않게)</li>
 * </ul>
 *
 * <p><b>적용 범위</b><br>
 * 스케줄러 리더 실행 한정(Task 035). 도메인 행 락({@code PESSIMISTIC_WRITE})에는 덧씌우지 않는다
 * (단일 PostgreSQL로 이미 노드 간 직렬화 — 이중 락·데드락 위험, ADR-005).
 *
 * <p><b>후속 스케줄러 가이드</b><br>
 * 신규 스케줄러(재고/주문 배치·Modulith 재발행 등)는 동일 {@code runIfLeader(resource, task)} 규약을 따른다:
 * {@code leaderGuard.runIfLeader("scheduler:{name}", this::doWork)}.
 */
public interface SchedulerLeaderGuard {

    /**
     * 리더 락을 비대기로 획득한 경우에만 {@code task}를 실행한다.
     *
     * @param resource 락 리소스 식별자 (예: {@code "scheduler:unpaid-order-expiry"}).
     *                 실제 락 키는 {@code RedisProperties.lock().prefix() + resource}로 조립된다.
     * @param task     리더 노드에서만 실행할 작업
     * @return {@code true} — 리더 획득 성공·task 실행 완료.
     *         {@code false} — 타 노드가 리더(즉시 skip) 또는 인터럽트·Redis 장애(폴백 skip).
     *         호출부는 false 시 "타 노드 리더, skip" 로깅을 권장한다.
     */
    boolean runIfLeader(String resource, Runnable task);
}
