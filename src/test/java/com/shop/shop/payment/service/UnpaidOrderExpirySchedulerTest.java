package com.shop.shop.payment.service;

import com.shop.shop.common.concurrency.SchedulerLeaderGuard;
import com.shop.shop.order.spi.OrderExpiryReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UnpaidOrderExpiryScheduler 단위 테스트 (022 신규 · 035 보강, Mockito).
 *
 * <p><b>035 보강</b>: guard fake를 주입해
 * (a) 리더면 위임 실행, (b) 비리더면 위임 0회 시나리오를 추가 검증한다.
 *
 * <p>guard 시나리오:
 * <ul>
 *   <li>리더 guard: {@code runIfLeader(resource, task)} 호출 시 task를 즉시 실행하고 true 반환</li>
 *   <li>비리더 guard: task 미실행 + false 반환</li>
 * </ul>
 *
 * <p>기존 022 검증:
 * <ul>
 *   <li>findExpiredPendingOrderIds(threshold, batchLimit) 결과를 각 expirePendingOrder로 위임</li>
 *   <li>격리: 한 주문 expirePendingOrder 예외가 다음 주문 처리를 막지 않음</li>
 *   <li>배치 한도: batchLimit이 findExpiredPendingOrderIds에 전달됨</li>
 *   <li>클록 주입: threshold = clock.instant() - ttl로 계산</li>
 *   <li>빈 목록: 처리 없음</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UnpaidOrderExpirySchedulerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private OrderExpiryReader orderExpiryReader;
    @Mock
    private SchedulerLeaderGuard leaderGuard;

    private static final Instant FIXED_NOW = Instant.parse("2026-06-12T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final int BATCH_LIMIT = 50;

    private Clock fixedClock;
    private OrderExpiryProperties properties;

    /** 리더 guard: runIfLeader 호출 시 task를 즉시 실행하고 true 반환 */
    private SchedulerLeaderGuard leaderGuardFake;
    /** 비리더 guard: task 미실행 + false 반환 */
    private SchedulerLeaderGuard nonLeaderGuardFake;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        properties = new OrderExpiryProperties(true, TTL, INTERVAL, BATCH_LIMIT);

        // 리더 guard fake: task 실행 + true
        leaderGuardFake = (resource, task) -> {
            task.run();
            return true;
        };

        // 비리더 guard fake: task 미실행 + false
        nonLeaderGuardFake = (resource, task) -> false;
    }

    // ============================================================
    // 035 보강 — guard 리더/비리더 시나리오
    // ============================================================

    @Test
    @DisplayName("[035] 리더 guard: expireUnpaidOrders 호출 시 doExpireUnpaidOrders 위임 실행")
    void expireUnpaidOrders_leader_guard_executes_work() {
        // given — 리더 guard fake 주입
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        List<Long> ids = List.of(1L, 2L);
        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT)).thenReturn(ids);

        // when
        scheduler.expireUnpaidOrders();

        // then — 위임 실행됨
        verify(paymentService).expirePendingOrder(1L);
        verify(paymentService).expirePendingOrder(2L);
    }

    @Test
    @DisplayName("[035] 비리더 guard: expireUnpaidOrders 호출 시 expirePendingOrder 0회 (skip)")
    void expireUnpaidOrders_non_leader_guard_skips_work() {
        // given — 비리더 guard fake 주입
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, nonLeaderGuardFake);

        // when
        scheduler.expireUnpaidOrders();

        // then — 위임 미실행 (비리더이므로 doExpireUnpaidOrders가 호출되지 않음)
        verifyNoInteractions(paymentService);
        verifyNoInteractions(orderExpiryReader);
    }

    @Test
    @DisplayName("[035] 비리더 guard: findExpiredPendingOrderIds도 0회 호출")
    void expireUnpaidOrders_non_leader_guard_no_reader_call() {
        // given — 비리더 guard fake 주입
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, nonLeaderGuardFake);

        // when
        scheduler.expireUnpaidOrders();

        // then — 조회도 미실행
        verifyNoInteractions(orderExpiryReader);
    }

    // ============================================================
    // 022 기존 — 위임 + threshold 계산 (리더 guard 사용)
    // ============================================================

    @Test
    @DisplayName("threshold = clock.instant() - ttl로 계산, batchLimit 전달")
    void expireUnpaidOrders_calculatesThresholdAndPassesBatchLimit() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        Instant expectedThreshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(expectedThreshold, BATCH_LIMIT))
                .thenReturn(List.of());

        // when
        scheduler.expireUnpaidOrders();

        // then
        verify(orderExpiryReader).findExpiredPendingOrderIds(eq(expectedThreshold), eq(BATCH_LIMIT));
    }

    @Test
    @DisplayName("id 목록을 각 expirePendingOrder로 위임")
    void expireUnpaidOrders_delegatesEachIdToExpirePendingOrder() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        List<Long> ids = List.of(1L, 2L, 3L);
        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT)).thenReturn(ids);

        // when
        scheduler.expireUnpaidOrders();

        // then
        verify(paymentService).expirePendingOrder(1L);
        verify(paymentService).expirePendingOrder(2L);
        verify(paymentService).expirePendingOrder(3L);
        verifyNoMoreInteractions(paymentService);
    }

    // ============================================================
    // 022 기존 — 격리: 한 주문 예외가 다음 주문 처리를 막지 않음
    // ============================================================

    @Test
    @DisplayName("격리: orderId=2에서 예외 발생해도 orderId=1·3 정상 처리")
    void expireUnpaidOrders_oneOrderFails_othersStillProcessed() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT))
                .thenReturn(List.of(1L, 2L, 3L));

        doNothing().when(paymentService).expirePendingOrder(1L);
        doThrow(new RuntimeException("처리 실패")).when(paymentService).expirePendingOrder(2L);
        doNothing().when(paymentService).expirePendingOrder(3L);

        // when — 예외가 루프를 멈추지 않아야 함
        scheduler.expireUnpaidOrders();

        // then — 1·2·3 모두 시도됨 (2는 예외지만 3은 계속 처리)
        verify(paymentService).expirePendingOrder(1L);
        verify(paymentService).expirePendingOrder(2L);
        verify(paymentService).expirePendingOrder(3L);
    }

    @Test
    @DisplayName("격리: 첫 번째 주문 예외 후 나머지 주문 전부 처리")
    void expireUnpaidOrders_firstOrderFails_restProcessed() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT))
                .thenReturn(List.of(10L, 20L, 30L));

        doThrow(new RuntimeException("첫 주문 실패")).when(paymentService).expirePendingOrder(10L);

        // when
        scheduler.expireUnpaidOrders();

        // then
        verify(paymentService).expirePendingOrder(10L);
        verify(paymentService).expirePendingOrder(20L);
        verify(paymentService).expirePendingOrder(30L);
    }

    // ============================================================
    // 022 기존 — 배치 한도
    // ============================================================

    @Test
    @DisplayName("배치 한도 BATCH_LIMIT이 findExpiredPendingOrderIds에 전달됨")
    void expireUnpaidOrders_batchLimitPassedToReader() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(any(Instant.class), eq(BATCH_LIMIT)))
                .thenReturn(List.of());

        // when
        scheduler.expireUnpaidOrders();

        // then
        verify(orderExpiryReader).findExpiredPendingOrderIds(any(Instant.class), eq(BATCH_LIMIT));
    }

    // ============================================================
    // 022 기존 — 빈 목록
    // ============================================================

    @Test
    @DisplayName("만료 대상 없음 — expirePendingOrder 미호출")
    void expireUnpaidOrders_emptyList_noExpireCall() {
        // given
        UnpaidOrderExpiryScheduler scheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock, leaderGuardFake);

        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT)).thenReturn(List.of());

        // when
        scheduler.expireUnpaidOrders();

        // then
        verifyNoInteractions(paymentService);
    }

    // ============================================================
    // 022 기존 — 클록 주입 (다른 고정 클록)
    // ============================================================

    @Test
    @DisplayName("클록 주입: 다른 고정 클록으로 threshold가 달라짐")
    void expireUnpaidOrders_differentClock_differentThreshold() {
        // given — 다른 고정 시각
        Instant otherNow = Instant.parse("2026-06-12T15:00:00Z");
        Clock otherClock = Clock.fixed(otherNow, ZoneOffset.UTC);
        UnpaidOrderExpiryScheduler otherScheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, otherClock, leaderGuardFake);

        Instant expectedThreshold = otherNow.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(expectedThreshold, BATCH_LIMIT))
                .thenReturn(List.of());

        // when
        otherScheduler.expireUnpaidOrders();

        // then
        verify(orderExpiryReader).findExpiredPendingOrderIds(eq(expectedThreshold), anyInt());
    }
}
