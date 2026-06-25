package com.shop.shop.common.events;

import com.shop.shop.common.concurrency.SchedulerLeaderGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * IncompletePublicationResubmitScheduler 단위 테스트 (Task 064, Mockito).
 *
 * <p>선례 {@code UnpaidOrderExpirySchedulerTest}의 guard-fake 패턴을 그대로 계승한다.
 *
 * <p>guard 시나리오:
 * <ul>
 *   <li>리더 guard: {@code runIfLeader(resource, task)} 호출 시 task를 즉시 실행하고 true 반환</li>
 *   <li>비리더 guard: task 미실행 + false 반환</li>
 * </ul>
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) 리더 → {@code resubmitIncompletePublicationsOlderThan(eq(olderThan))} 정확히 1회</li>
 *   <li>(b) 비리더 → {@code verifyNoInteractions(incompleteEventPublications)} (skip)</li>
 *   <li>(c) 락 장애(guard false) → 위임 0회</li>
 *   <li>(d) 올바른 olderThan 전달 — properties.olderThan()이 그대로 API에 전달됨</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IncompletePublicationResubmitSchedulerTest {

    @Mock
    private IncompleteEventPublications incompleteEventPublications;

    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final Duration OLDER_THAN = Duration.ofMinutes(2);

    private EventRepublishProperties properties;

    /** 리더 guard: runIfLeader 호출 시 task를 즉시 실행하고 true 반환 */
    private SchedulerLeaderGuard leaderGuardFake;

    /** 비리더 guard: task 미실행 + false 반환 */
    private SchedulerLeaderGuard nonLeaderGuardFake;

    @BeforeEach
    void setUp() {
        properties = new EventRepublishProperties(true, INTERVAL, OLDER_THAN);

        // 리더 guard fake: task 실행 + true
        leaderGuardFake = (resource, task) -> {
            task.run();
            return true;
        };

        // 비리더 guard fake: task 미실행 + false
        nonLeaderGuardFake = (resource, task) -> false;
    }

    // ============================================================
    // (a) 리더 → resubmitIncompletePublicationsOlderThan 1회 호출
    // ============================================================

    @Test
    @DisplayName("(a) 리더 guard: resubmitIncomplete 호출 시 resubmitIncompletePublicationsOlderThan 1회 위임")
    void resubmitIncomplete_leader_guard_executes_resubmit() {
        // given — 리더 guard fake 주입
        IncompletePublicationResubmitScheduler scheduler =
                new IncompletePublicationResubmitScheduler(incompleteEventPublications, properties, leaderGuardFake);

        // when
        scheduler.resubmitIncomplete();

        // then — olderThan이 정확히 전달되어 1회 호출
        verify(incompleteEventPublications).resubmitIncompletePublicationsOlderThan(eq(OLDER_THAN));
    }

    // ============================================================
    // (b) 비리더 → verifyNoInteractions
    // ============================================================

    @Test
    @DisplayName("(b) 비리더 guard: resubmitIncomplete 호출 시 incompleteEventPublications 0회 (skip)")
    void resubmitIncomplete_non_leader_guard_skips_work() {
        // given — 비리더 guard fake 주입
        IncompletePublicationResubmitScheduler scheduler =
                new IncompletePublicationResubmitScheduler(incompleteEventPublications, properties, nonLeaderGuardFake);

        // when
        scheduler.resubmitIncomplete();

        // then — 비리더이므로 doResubmit가 호출되지 않음
        verifyNoInteractions(incompleteEventPublications);
    }

    // ============================================================
    // (c) 락 장애(guard false) → 위임 0회
    // ============================================================

    @Test
    @DisplayName("(c) 락 장애(guard false): incompleteEventPublications 0회 (폴백 skip)")
    void resubmitIncomplete_lock_failure_guard_skips_work() {
        // given — 락 장애 시뮬레이트: false 반환, task 미실행 (비리더 guard fake와 동형)
        SchedulerLeaderGuard lockFailureGuardFake = (resource, task) -> false;
        IncompletePublicationResubmitScheduler scheduler =
                new IncompletePublicationResubmitScheduler(incompleteEventPublications, properties, lockFailureGuardFake);

        // when
        scheduler.resubmitIncomplete();

        // then — 락 장애이므로 재제출 미실행
        verifyNoInteractions(incompleteEventPublications);
    }

    // ============================================================
    // (d) 올바른 olderThan 전달 — 커스텀 PT5M 값
    // ============================================================

    @Test
    @DisplayName("(d) 올바른 olderThan 전달: properties.olderThan()=PT5M이 그대로 API에 전달됨")
    void resubmitIncomplete_leader_guard_passes_correct_olderThan() {
        // given — 커스텀 olderThan(PT5M) 주입
        Duration customOlderThan = Duration.ofMinutes(5);
        EventRepublishProperties customProperties = new EventRepublishProperties(true, INTERVAL, customOlderThan);
        IncompletePublicationResubmitScheduler scheduler =
                new IncompletePublicationResubmitScheduler(incompleteEventPublications, customProperties, leaderGuardFake);

        // when
        scheduler.resubmitIncomplete();

        // then — PT5M이 그대로 전달됨
        verify(incompleteEventPublications).resubmitIncompletePublicationsOlderThan(eq(customOlderThan));
    }

    @Test
    @DisplayName("(d) compact constructor 기본값: null olderThan → PT2M 폴백")
    void eventRepublishProperties_null_olderThan_falls_back_to_default() {
        // given — olderThan=null → compact constructor가 PT2M으로 폴백
        EventRepublishProperties defaultProperties = new EventRepublishProperties(true, INTERVAL, null);
        IncompletePublicationResubmitScheduler scheduler =
                new IncompletePublicationResubmitScheduler(incompleteEventPublications, defaultProperties, leaderGuardFake);

        // when
        scheduler.resubmitIncomplete();

        // then — PT2M(기본값) 전달
        verify(incompleteEventPublications).resubmitIncompletePublicationsOlderThan(eq(Duration.ofMinutes(2)));
    }
}
