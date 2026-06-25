package com.shop.shop.common.events;

import com.shop.shop.common.concurrency.SchedulerLeaderGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 미완료 발행 상시 회복 스케줄러 — package-private (Task 064).
 *
 * <p>주기적으로 {@code event_publication}의 일정 시간 이상 미완료(INCOMPLETE, {@code completion_date IS NULL})인
 * 발행을 재제출해 Kafka로 재외부화한다. 재기동을 기다리지 않고 영구 실패한 발행을 상시 회복한다.
 *
 * <p>다중 노드에서는 {@link SchedulerLeaderGuard#runIfLeader}로 리더 1노드만 실행해
 * 중복 외부화를 축소한다(best-effort). 락 장애 시 false 반환 → skip 폴백(선례 동일).
 *
 * <p>재제출 메커니즘은 Modulith {@link IncompleteEventPublications#resubmitIncompletePublicationsOlderThan(Duration)}에
 * 위임한다 — "older-than" 기반이므로 프로듀서가 아직 자동 재시도 중인(방금 발행된) 건을 제외한다.
 *
 * <p><b>활성화 가드 ({@code @ConditionalOnProperty})</b>:
 * {@code shop.events.republish.enabled=true}일 때만 빈으로 등록된다.
 * 테스트 컨텍스트({@code src/test/resources/application.yml}: {@code enabled: false})에서는
 * 이 빈이 생성되지 않아 백그라운드 실행이 없다(verification-gate §4).
 *
 * <p>{@code @Transactional} 미부착 — {@code resubmitIncompletePublicationsOlderThan}은
 * Modulith가 내부에서 DB 재조회·외부화 재시도를 자체 트랜잭션 경계로 수행하므로 호출부에 트랜잭션을 두지 않는다.
 *
 * <p>가상스레드 대비: {@code ThreadLocal} 직접 사용 없음, 블로킹 I/O는 Service/Infra 경계(CLAUDE.md).
 *
 * <p>063(재기동 회복)과 상호보완·동시 활성 무해(대상 동일 INCOMPLETE, 멱등).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.events.republish", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class IncompletePublicationResubmitScheduler {

    /** 분산락 리소스 식별자 — 실제 락 키: {@code shopcore:lock:scheduler:event-republish} */
    private static final String SCHEDULER_RESOURCE = "scheduler:event-republish";

    private final IncompleteEventPublications incompleteEventPublications; // Modulith 멀티캐스터 빈
    private final EventRepublishProperties properties;
    private final SchedulerLeaderGuard leaderGuard;

    /**
     * 미완료 발행 재제출 주기 실행 — 분산락 리더 게이트 진입점.
     *
     * <p>다중 노드 중 리더 락을 획득한 노드만 {@link #doResubmit()}를 실행한다.
     * 비리더 노드는 즉시 skip(대기 없음). 락 인프라 장애 시도 skip(폴백).
     *
     * <p>{@code @Transactional} 미부착 — Modulith가 내부 트랜잭션 경계로 수행.
     */
    @Scheduled(fixedDelayString = "${shop.events.republish.interval:PT1M}")
    public void resubmitIncomplete() {
        boolean isLeader = leaderGuard.runIfLeader(SCHEDULER_RESOURCE, this::doResubmit);
        if (!isLeader) {
            log.debug("미완료 발행 재제출 스케줄 — 타 노드가 리더 또는 락 장애, 이번 주기 skip. resource={}", SCHEDULER_RESOURCE);
        }
    }

    /**
     * 실제 재제출 로직 — 리더 노드에서만 실행된다.
     *
     * <p>older-than 초과 미완료 발행만 재제출 대상(프로듀서 재시도 중인 건 제외).
     * 재제출 성공 시 Modulith가 {@code completion_date}를 기록한다(회복 완료).
     * 재외부화가 또 실패하면 해당 건은 다시 INCOMPLETE로 남아 다음 주기에 재시도(유실 없음).
     */
    private void doResubmit() {
        Duration olderThan = properties.olderThan();
        log.debug("미완료 발행 재제출 시작: olderThan={}", olderThan);
        incompleteEventPublications.resubmitIncompletePublicationsOlderThan(olderThan);
        log.info("미완료 발행 재제출 트리거 완료: olderThan={}", olderThan);
    }
}
