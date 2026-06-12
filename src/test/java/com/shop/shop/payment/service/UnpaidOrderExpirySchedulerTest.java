package com.shop.shop.payment.service;

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
 * UnpaidOrderExpiryScheduler 단위 테스트 (022 신규, Mockito).
 *
 * <p>검증:
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

    private static final Instant FIXED_NOW = Instant.parse("2026-06-12T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final int BATCH_LIMIT = 50;

    private Clock fixedClock;
    private OrderExpiryProperties properties;
    private UnpaidOrderExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        properties = new OrderExpiryProperties(true, TTL, INTERVAL, BATCH_LIMIT);
        scheduler = new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, fixedClock);
    }

    // ============================================================
    // 위임 + threshold 계산
    // ============================================================

    @Test
    @DisplayName("threshold = clock.instant() - ttl로 계산, batchLimit 전달")
    void expireUnpaidOrders_calculatesThresholdAndPassesBatchLimit() {
        // given
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
    // 격리: 한 주문 예외가 다음 주문 처리를 막지 않음
    // ============================================================

    @Test
    @DisplayName("격리: orderId=2에서 예외 발생해도 orderId=1·3 정상 처리")
    void expireUnpaidOrders_oneOrderFails_othersStillProcessed() {
        // given
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
    // 배치 한도
    // ============================================================

    @Test
    @DisplayName("배치 한도 BATCH_LIMIT이 findExpiredPendingOrderIds에 전달됨")
    void expireUnpaidOrders_batchLimitPassedToReader() {
        // given
        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(any(Instant.class), eq(BATCH_LIMIT)))
                .thenReturn(List.of());

        // when
        scheduler.expireUnpaidOrders();

        // then
        verify(orderExpiryReader).findExpiredPendingOrderIds(any(Instant.class), eq(BATCH_LIMIT));
    }

    // ============================================================
    // 빈 목록
    // ============================================================

    @Test
    @DisplayName("만료 대상 없음 — expirePendingOrder 미호출")
    void expireUnpaidOrders_emptyList_noExpireCall() {
        // given
        Instant threshold = FIXED_NOW.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(threshold, BATCH_LIMIT)).thenReturn(List.of());

        // when
        scheduler.expireUnpaidOrders();

        // then
        verifyNoInteractions(paymentService);
    }

    // ============================================================
    // 클록 주입 (다른 고정 클록)
    // ============================================================

    @Test
    @DisplayName("클록 주입: 다른 고정 클록으로 threshold가 달라짐")
    void expireUnpaidOrders_differentClock_differentThreshold() {
        // given — 다른 고정 시각
        Instant otherNow = Instant.parse("2026-06-12T15:00:00Z");
        Clock otherClock = Clock.fixed(otherNow, ZoneOffset.UTC);
        UnpaidOrderExpiryScheduler otherScheduler =
                new UnpaidOrderExpiryScheduler(paymentService, orderExpiryReader, properties, otherClock);

        Instant expectedThreshold = otherNow.minus(TTL);
        when(orderExpiryReader.findExpiredPendingOrderIds(expectedThreshold, BATCH_LIMIT))
                .thenReturn(List.of());

        // when
        otherScheduler.expireUnpaidOrders();

        // then
        verify(orderExpiryReader).findExpiredPendingOrderIds(eq(expectedThreshold), anyInt());
    }
}
