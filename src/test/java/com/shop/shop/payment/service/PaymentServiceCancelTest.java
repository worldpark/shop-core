package com.shop.shop.payment.service;

import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderSnapshotView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentRefundRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentRefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * PaymentService.cancel 단위 테스트 (018 신규, Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>paid 주문 → PG refund 호출 + Payment.markRefunded + OrderCancellation.cancel(RefundInfo.refunded=true)</li>
 *   <li>pending 주문(결제 row 있음) → refund 미호출 + Payment.markCancelled + OrderCancellation.cancel(RefundInfo.refunded=false)</li>
 *   <li>pending 주문(결제 row 없음) → refund 미호출 + OrderCancellation.cancel(RefundInfo.refunded=false)</li>
 *   <li>이행단계(preparing/shipping/delivered) → OrderCancellationConflictException(409), refund·복원·이벤트·OrderCancellation 위임 미수행</li>
 *   <li>이미 cancelled/refunded → 멱등 반환(CancelResult.alreadyCancelled=true), refund 미호출</li>
 *   <li>타인 주문 → OrderNotFoundException(404)</li>
 *   <li>#4 순서: locked reader(getOrderForCancel)가 PG refund보다 먼저 호출 (InOrder)</li>
 *   <li>(방어) OrderCancellation.cancel이 REJECTED 반환 → IllegalStateException(500, 락 불변식 위반)</li>
 *   <li>(방어) OrderCancellation.cancel이 ALREADY_CANCELLED 반환 → IllegalStateException(500, 락 불변식 위반)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceCancelTest {

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
    private static final long USER_ID = 7L;
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(20000);

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                orderPaymentReader, orderConfirmation, orderCancellation,
                paymentGatewayPort, paymentRepository, memberDirectory, eventPublisher);
    }

    // ============================================================
    // paid 주문 취소 — 환불 경로
    // ============================================================

    @Test
    @DisplayName("paid 주문 → PG refund 호출 + OrderCancellation.cancel(refunded=true)")
    void cancel_paidOrder_callsRefundAndCancellation() {
        // given
        OrderSnapshotView snapshot = snapshot("paid");
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot);

        Payment payment = buildPayment("paid");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        when(paymentGatewayPort.refund(any()))
                .thenReturn(PaymentRefundResult.refunded("MOCK-REFUND-pay-id"));

        OrderCancellationResult cancellationResult = buildCancelledResult("refunded");
        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(cancellationResult);

        // when
        PaymentService.CancelResult result = paymentService.cancel(USER_ID, ORDER_ID);

        // then
        verify(paymentGatewayPort).refund(any(PaymentRefundRequest.class));

        ArgumentCaptor<RefundInfo> refundInfoCaptor = ArgumentCaptor.forClass(RefundInfo.class);
        verify(orderCancellation).cancel(eq(ORDER_ID), eq(USER_ID), refundInfoCaptor.capture());
        RefundInfo refundInfo = refundInfoCaptor.getValue();
        assertThat(refundInfo.refunded()).isTrue();
        assertThat(refundInfo.refundedAmount()).isEqualTo(AMOUNT.longValue());

        assertThat(result.isRefunded()).isTrue();
        assertThat(result.orderStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("#4 순서 검증: getOrderForCancel이 PG refund보다 먼저 호출")
    void cancel_paidOrder_lockedReaderBeforeRefund_inOrder() {
        // given
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("paid"));
        Payment payment = buildPayment("paid");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(paymentGatewayPort.refund(any())).thenReturn(PaymentRefundResult.refunded("MOCK-REFUND-key"));
        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(buildCancelledResult("refunded"));

        // when
        paymentService.cancel(USER_ID, ORDER_ID);

        // then — InOrder: locked reader가 refund보다 먼저 (#4)
        InOrder inOrder = inOrder(orderPaymentReader, paymentGatewayPort);
        inOrder.verify(orderPaymentReader).getOrderForCancel(ORDER_ID, USER_ID);
        inOrder.verify(paymentGatewayPort).refund(any());
    }

    // ============================================================
    // pending 주문 취소 — 미결제 경로
    // ============================================================

    @Test
    @DisplayName("pending 주문(결제 row 있음) → refund 미호출 + markCancelled + OrderCancellation.cancel(refunded=false)")
    void cancel_pendingOrder_withPaymentRow_marksCancelled() {
        // given
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("pending"));

        Payment payment = buildPayment("ready");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(buildCancelledResult("cancelled"));

        // when
        PaymentService.CancelResult result = paymentService.cancel(USER_ID, ORDER_ID);

        // then
        verifyNoInteractions(paymentGatewayPort);

        ArgumentCaptor<RefundInfo> refundInfoCaptor = ArgumentCaptor.forClass(RefundInfo.class);
        verify(orderCancellation).cancel(eq(ORDER_ID), eq(USER_ID), refundInfoCaptor.capture());
        assertThat(refundInfoCaptor.getValue().refunded()).isFalse();
        assertThat(refundInfoCaptor.getValue().refundedAmount()).isEqualTo(0L);

        assertThat(result.isRefunded()).isFalse();
        assertThat(result.orderStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("pending 주문(결제 row 없음) → refund 미호출 + OrderCancellation.cancel(refunded=false)")
    void cancel_pendingOrder_noPaymentRow_callsCancellationWithRefundedFalse() {
        // given
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(buildCancelledResult("cancelled"));

        // when
        PaymentService.CancelResult result = paymentService.cancel(USER_ID, ORDER_ID);

        // then
        verifyNoInteractions(paymentGatewayPort);
        verify(orderCancellation).cancel(eq(ORDER_ID), eq(USER_ID), any(RefundInfo.class));
        assertThat(result.isRefunded()).isFalse();
    }

    // ============================================================
    // 이행단계 — 409 거부 (PG refund 전 throw)
    // ============================================================

    @Test
    @DisplayName("이행단계(preparing) → 409 OrderCancellationConflictException, refund·OrderCancellation 미수행")
    void cancel_preparing_throwsConflict_beforeRefund() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("preparing"));

        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(OrderCancellationConflictException.class);

        verifyNoInteractions(paymentGatewayPort);
        verifyNoInteractions(orderCancellation);
    }

    @Test
    @DisplayName("이행단계(shipping) → 409, 부작용 없음")
    void cancel_shipping_throwsConflict() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("shipping"));

        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(OrderCancellationConflictException.class);

        verifyNoInteractions(paymentGatewayPort);
        verifyNoInteractions(orderCancellation);
    }

    @Test
    @DisplayName("이행단계(delivered) → 409, 부작용 없음")
    void cancel_delivered_throwsConflict() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("delivered"));

        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(OrderCancellationConflictException.class);

        verifyNoInteractions(paymentGatewayPort);
        verifyNoInteractions(orderCancellation);
    }

    // ============================================================
    // 멱등 재취소 — 이미 cancelled/refunded
    // ============================================================

    @Test
    @DisplayName("이미 cancelled 주문 재취소 → 멱등 반환(alreadyCancelled=true, refunded=false, refundedAmount=0), refund 미호출")
    void cancel_alreadyCancelled_idempotentReturn() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("cancelled"));

        PaymentService.CancelResult result = paymentService.cancel(USER_ID, ORDER_ID);

        // B안: already-cancelled → 최초 cancelled() 응답과 동일한 표현
        assertThat(result.alreadyCancelled()).isTrue();
        assertThat(result.orderStatus()).isEqualTo("cancelled");
        assertThat(result.isRefunded()).isFalse();
        assertThat(result.refundedAmount()).isEqualTo(0L);
        assertThat(result.currency()).isEqualTo("KRW");
        verifyNoInteractions(paymentGatewayPort);
        verifyNoInteractions(orderCancellation);
    }

    @Test
    @DisplayName("이미 refunded 주문 재취소 → 멱등 반환(alreadyCancelled=true, refunded=true, refundedAmount=finalAmount), refund 미호출")
    void cancel_alreadyRefunded_idempotentReturn() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("refunded"));

        PaymentService.CancelResult result = paymentService.cancel(USER_ID, ORDER_ID);

        // B안: already-refunded → 최초 refunded() 응답과 동일한 표현
        assertThat(result.alreadyCancelled()).isTrue();
        assertThat(result.orderStatus()).isEqualTo("refunded");
        assertThat(result.isRefunded()).isTrue();
        assertThat(result.refundedAmount()).isEqualTo(AMOUNT.longValue()); // finalAmount = 20000L
        assertThat(result.currency()).isEqualTo("KRW");
        verifyNoInteractions(paymentGatewayPort);
        verifyNoInteractions(orderCancellation);
    }

    // ============================================================
    // 소유권 404
    // ============================================================

    @Test
    @DisplayName("타인/미존재 주문 → OrderNotFoundException(404)")
    void cancel_otherUserOrder_throws404() {
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID))
                .thenThrow(new OrderNotFoundException());

        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ============================================================
    // 방어 검증 — OrderCancellation이 REJECTED/ALREADY_CANCELLED 반환
    // ============================================================

    @Test
    @DisplayName("(방어) OrderCancellation이 REJECTED 반환 → IllegalStateException(500, 락 불변식 위반)")
    void cancel_cancellationRejected_throwsIllegalStateException() {
        // given
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        OrderCancellationResult rejectedResult = new OrderCancellationResult(
                ORDER_ID, "ORD-MOCK", OrderCancellation.Outcome.REJECTED,
                "pending", false, null, "방어 테스트");
        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(rejectedResult);

        // when/then
        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("(방어) OrderCancellation이 ALREADY_CANCELLED 반환 → IllegalStateException(500, 락 불변식 위반)")
    void cancel_cancellationAlreadyCancelled_throwsIllegalStateException() {
        // given
        when(orderPaymentReader.getOrderForCancel(ORDER_ID, USER_ID)).thenReturn(snapshot("pending"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        OrderCancellationResult alreadyResult = new OrderCancellationResult(
                ORDER_ID, "ORD-MOCK", OrderCancellation.Outcome.ALREADY_CANCELLED,
                "cancelled", false, null, null);
        when(orderCancellation.cancel(anyLong(), anyLong(), any())).thenReturn(alreadyResult);

        // when/then
        assertThatThrownBy(() -> paymentService.cancel(USER_ID, ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALREADY_CANCELLED");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderSnapshotView snapshot(String status) {
        return new OrderSnapshotView(ORDER_ID, "ORD-MOCK-001", USER_ID, status, AMOUNT, "KRW");
    }

    private Payment buildPayment(String status) {
        Payment p = Payment.create(ORDER_ID, "mock", AMOUNT);
        if ("paid".equals(status)) {
            p.markPaid("MOCK-TX-001", Instant.now());
        }
        return p;
    }

    private OrderCancellationResult buildCancelledResult(String orderStatus) {
        return new OrderCancellationResult(
                ORDER_ID, "ORD-MOCK-001",
                OrderCancellation.Outcome.CANCELLED,
                orderStatus, true, Instant.now(), null);
    }
}
