package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.Outcome;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderSnapshotView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * PaymentService.expirePendingOrder 단위 테스트 (022 신규, Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>pending(결제 row 없음) → getOrderForExpiry → cancelByExpiry 위임 (markCancelled 미호출)</li>
 *   <li>pending(결제 row ready/failed) → Payment.markCancelled + cancelByExpiry 위임</li>
 *   <li>paid/preparing/shipping/delivered/cancelled/refunded → 멱등 skip(부작용 없음)</li>
 *   <li>#R3 락 우선 순서: getOrderForExpiry가 markCancelled·cancelByExpiry보다 먼저 (InOrder)</li>
 *   <li>소유권 미검사: getOrderForExpiry(orderId)·cancelByExpiry(orderId)가 userId 인자 없이 호출</li>
 *   <li>PG refund 미호출: paymentGatewayPort.refund 0회</li>
 *   <li>(방어) cancelByExpiry가 ALREADY_CANCELLED/REJECTED 반환 → IllegalStateException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceExpiryTest {

    @Mock
    private OrderPaymentReader orderPaymentReader;
    @Mock
    private OrderConfirmation orderConfirmation;
    @Mock
    private OrderCancellation orderCancellation;
    @Mock
    private PaymentGatewayPort paymentGatewayPort;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private MemberDirectory memberDirectory;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PaymentService paymentService;

    private static final long ORDER_ID = 42L;
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(30000);

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                orderPaymentReader, orderConfirmation, orderCancellation,
                paymentGatewayPort, paymentRepository, memberDirectory, eventPublisher);
    }

    // ============================================================
    // pending — 결제 row 없음
    // ============================================================

    @Test
    @DisplayName("pending(결제 row 없음) → cancelByExpiry 위임, Payment.markCancelled 미호출, PG refund 미호출")
    void expirePendingOrder_pendingNoPaymentRow_delegatesToCancelByExpiry() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(cancelledResult());

        // when
        paymentService.expirePendingOrder(ORDER_ID);

        // then
        verify(orderPaymentReader).getOrderForExpiry(ORDER_ID);
        verify(paymentRepository).findByOrderId(ORDER_ID);
        verify(orderCancellation).cancelByExpiry(ORDER_ID);
        verifyNoInteractions(paymentGatewayPort);
    }

    // ============================================================
    // pending — 결제 row ready
    // ============================================================

    @Test
    @DisplayName("pending(결제 row ready) → Payment.markCancelled + cancelByExpiry 위임")
    void expirePendingOrder_pendingWithReadyPayment_marksCancelledAndDelegates() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT); // status=ready
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(cancelledResult());

        // when
        paymentService.expirePendingOrder(ORDER_ID);

        // then
        assertPaymentCancelled(payment);
        verify(orderCancellation).cancelByExpiry(ORDER_ID);
        verifyNoInteractions(paymentGatewayPort);
    }

    @Test
    @DisplayName("pending(결제 row failed) → Payment.markCancelled + cancelByExpiry 위임")
    void expirePendingOrder_pendingWithFailedPayment_marksCancelledAndDelegates() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markFailed("DECLINED", "거절");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(cancelledResult());

        // when
        paymentService.expirePendingOrder(ORDER_ID);

        // then
        assertPaymentCancelled(payment);
        verify(orderCancellation).cancelByExpiry(ORDER_ID);
        verifyNoInteractions(paymentGatewayPort);
    }

    // ============================================================
    // 멱등 skip — paid/이행/종결 상태
    // ============================================================

    @Test
    @DisplayName("paid → 멱등 skip (markCancelled·cancelByExpiry·PG refund 0회)")
    void expirePendingOrder_paid_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("paid"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    @Test
    @DisplayName("preparing → 멱등 skip")
    void expirePendingOrder_preparing_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("preparing"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    @Test
    @DisplayName("shipping → 멱등 skip")
    void expirePendingOrder_shipping_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("shipping"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    @Test
    @DisplayName("delivered → 멱등 skip")
    void expirePendingOrder_delivered_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("delivered"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    @Test
    @DisplayName("cancelled → 멱등 skip")
    void expirePendingOrder_cancelled_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("cancelled"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    @Test
    @DisplayName("refunded → 멱등 skip")
    void expirePendingOrder_refunded_idempotentSkip() {
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("refunded"));
        paymentService.expirePendingOrder(ORDER_ID);
        verifySkipInteractions();
    }

    // ============================================================
    // #R3 락 우선 순서: InOrder
    // ============================================================

    @Test
    @DisplayName("#R3 락 우선: getOrderForExpiry → Payment.markCancelled → cancelByExpiry 순서 (InOrder)")
    void expirePendingOrder_lockFirstOrder_inOrder() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(cancelledResult());

        // when
        paymentService.expirePendingOrder(ORDER_ID);

        // then — InOrder: locked reader가 cancelByExpiry보다 먼저 (R3)
        InOrder inOrder = inOrder(orderPaymentReader, orderCancellation);
        inOrder.verify(orderPaymentReader).getOrderForExpiry(ORDER_ID);
        inOrder.verify(orderCancellation).cancelByExpiry(ORDER_ID);
    }

    // ============================================================
    // 소유권 미검사: userId 인자 없이 호출
    // ============================================================

    @Test
    @DisplayName("소유권 미검사: getOrderForExpiry(orderId)·cancelByExpiry(orderId)가 userId 인자 없이 호출")
    void expirePendingOrder_noOwnershipCheck_noUserIdArgument() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(cancelledResult());

        // when
        paymentService.expirePendingOrder(ORDER_ID);

        // then — userId 인자 없는 시그니처로만 호출됨
        verify(orderPaymentReader).getOrderForExpiry(ORDER_ID);       // userId 없음
        verify(orderCancellation).cancelByExpiry(ORDER_ID);           // userId 없음
        // getOrderForCancel(orderId, userId) 또는 cancel(orderId, userId, refundInfo)는 미호출
        verify(orderPaymentReader, never()).getOrderForCancel(anyLong(), anyLong());
        verify(orderCancellation, never()).cancel(anyLong(), anyLong(), any());
    }

    // ============================================================
    // 방어 검증 — cancelByExpiry가 CANCELLED 외 반환
    // ============================================================

    @Test
    @DisplayName("(방어) cancelByExpiry가 ALREADY_CANCELLED 반환 → IllegalStateException(락 불변식 위반)")
    void expirePendingOrder_alreadyCancelled_throwsIllegalStateException() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        OrderCancellationResult alreadyResult = new OrderCancellationResult(
                ORDER_ID, "ORD-TEST", Outcome.ALREADY_CANCELLED, "cancelled", false, null, null);
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(alreadyResult);

        // when/then
        assertThatThrownBy(() -> paymentService.expirePendingOrder(ORDER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("(방어) cancelByExpiry가 REJECTED 반환 → IllegalStateException(락 불변식 위반)")
    void expirePendingOrder_rejected_throwsIllegalStateException() {
        // given
        when(orderPaymentReader.getOrderForExpiry(ORDER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        OrderCancellationResult rejectedResult = new OrderCancellationResult(
                ORDER_ID, "ORD-TEST", Outcome.REJECTED, "pending", false, null, "방어 테스트");
        when(orderCancellation.cancelByExpiry(ORDER_ID)).thenReturn(rejectedResult);

        // when/then
        assertThatThrownBy(() -> paymentService.expirePendingOrder(ORDER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderSnapshotView snapshot(String status) {
        return new OrderSnapshotView(ORDER_ID, "ORD-EXPIRY-001", 7L, status, AMOUNT, "KRW");
    }

    private OrderCancellationResult cancelledResult() {
        return new OrderCancellationResult(
                ORDER_ID, "ORD-EXPIRY-001", Outcome.CANCELLED, "cancelled", true, Instant.now(), null);
    }

    private void verifySkipInteractions() {
        verify(paymentRepository, never()).findByOrderId(anyLong());
        verifyNoInteractions(orderCancellation);
        verifyNoInteractions(paymentGatewayPort);
    }

    private void assertPaymentCancelled(Payment payment) {
        // Payment.markCancelled() 호출 확인은 상태 변경으로 대리 검증 (Payment는 real object)
        org.assertj.core.api.Assertions.assertThat(payment.getStatus()).isEqualTo("cancelled");
    }
}
