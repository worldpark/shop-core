package com.shop.shop.payment.service;

import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentAmountMismatchException;
import com.shop.shop.common.exception.PaymentEventResolutionException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderConfirmation.OrderConfirmationResult;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderPaymentView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * PaymentService 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>8단계 처리 순서 (InOrder) — ready 선점 이후 authorize 호출</li>
 *   <li>이미 paid 재요청 → 멱등 반환 (authorize/confirmPaid 미호출)</li>
 *   <li>ready 선점 경합 → PaymentInProgressException (authorize 미호출)</li>
 *   <li>비정상 주문 상태 → OrderConfirmationConflictException (snapshot 단계)</li>
 *   <li>confirmPaid REJECTED 반환 → OrderConfirmationConflictException 되던짐 (롤백 보존)</li>
 *   <li>타인 주문 → OrderNotFoundException</li>
 *   <li>금액 불일치 → PaymentAmountMismatchException</li>
 *   <li>완결성 사전검증 실패 → PaymentEventResolutionException (PG 호출 전)</li>
 *   <li>payments.amount = finalAmount (서버 권위)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock private OrderPaymentReader orderPaymentReader;
    @Mock private OrderConfirmation orderConfirmation;
    @Mock private PaymentGatewayPort paymentGatewayPort;
    @Mock private PaymentRepository paymentRepository;
    @Mock private MemberDirectory memberDirectory;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentService paymentService;

    private static final long USER_ID = 1L;
    private static final long ORDER_ID = 100L;
    private static final String ORDER_NUMBER = "ORD-20260608-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                orderPaymentReader, orderConfirmation, paymentGatewayPort, paymentRepository,
                memberDirectory, eventPublisher);
        // 기본 연락처 mock — 승인 경로 테스트에서 연락처 해석 성공 가정
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact("user@example.com", "테스트유저"));
    }

    @Test
    @DisplayName("모의 PG 승인: 8단계 순서 — ready 선점 이후 authorize 호출(#2)")
    void pay_success_8StepOrder() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        Payment savedPayment = buildPayment(ORDER_ID, "ready", AMOUNT);
        when(paymentRepository.saveAndFlush(any())).thenReturn(savedPayment);
        when(paymentGatewayPort.authorize(any())).thenReturn(PaymentAuthorizationResult.approved("MOCK-TX-001"));

        OrderConfirmationResult confirmResult = new OrderConfirmationResult(
                ORDER_ID, ORDER_NUMBER, OrderConfirmation.Outcome.CONFIRMED, true, Instant.now(), null);
        when(orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT)).thenReturn(confirmResult);

        // when
        PaymentService.PaymentCommand cmd = new PaymentService.PaymentCommand(null, null);
        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID, cmd);

        // then — InOrder: saveAndFlush(선점) → authorize (PG 전 직렬화 #2)
        InOrder inOrder = inOrder(paymentRepository, paymentGatewayPort, orderConfirmation);
        inOrder.verify(paymentRepository).saveAndFlush(any());
        inOrder.verify(paymentGatewayPort).authorize(any());
        inOrder.verify(orderConfirmation).confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        assertThat(result.payment().getStatus()).isEqualTo("paid");
    }

    @Test
    @DisplayName("payments.amount = finalAmount(서버 권위) — 클라이언트 금액 미반영")
    void pay_amountFromFinalAmount_notFromClient() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        Payment savedPayment = buildPayment(ORDER_ID, "ready", AMOUNT);
        when(paymentRepository.saveAndFlush(paymentCaptor.capture())).thenReturn(savedPayment);
        when(paymentGatewayPort.authorize(any())).thenReturn(PaymentAuthorizationResult.approved("MOCK-TX-001"));
        when(orderConfirmation.confirmPaid(anyLong(), anyLong(), any()))
                .thenReturn(new OrderConfirmationResult(
                        ORDER_ID, ORDER_NUMBER, OrderConfirmation.Outcome.CONFIRMED, true, Instant.now(), null));

        // when — 클라이언트가 다른 금액 없이 호출
        paymentService.pay(USER_ID, ORDER_ID, new PaymentService.PaymentCommand(null, null));

        // then — 저장된 payment amount는 finalAmount와 일치
        Payment captured = paymentCaptor.getValue();
        assertThat(captured.getAmount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    @DisplayName("이미 paid 재요청 → 멱등 반환 (authorize/confirmPaid 미호출)")
    void pay_alreadyPaid_idempotentReturn() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "paid", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);

        Payment paidPayment = buildPayment(ORDER_ID, "paid", AMOUNT);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(paidPayment));

        // when
        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null));

        // then — authorize/confirmPaid 미호출
        verify(paymentGatewayPort, never()).authorize(any());
        verify(orderConfirmation, never()).confirmPaid(anyLong(), anyLong(), any());
        assertThat(result.payment().getStatus()).isEqualTo("paid");
    }

    @Test
    @DisplayName("ready 선점 경합 → PaymentInProgressException (authorize 미호출, #2)")
    void pay_readyRowConflict_throwsPaymentInProgressException() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq violation"));

        // 재조회 시 ready row 잔존
        Payment readyPayment = buildPayment(ORDER_ID, "ready", AMOUNT);
        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.empty())  // 첫 번째 조회
                .thenReturn(Optional.of(readyPayment)); // 재조회

        // when & then
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null)))
                .isInstanceOf(PaymentInProgressException.class);

        verify(paymentGatewayPort, never()).authorize(any());
    }

    @Test
    @DisplayName("비정상 주문 상태 → OrderConfirmationConflictException (authorize/payments 미생성)")
    void pay_nonPendingStatus_throwsConflict() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "cancelled", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);

        // when & then
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null)))
                .isInstanceOf(OrderConfirmationConflictException.class);

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentGatewayPort, never()).authorize(any());
    }

    @Test
    @DisplayName("타인 주문 → OrderNotFoundException (ready INSERT·authorize 미호출)")
    void pay_otherUsersOrder_throwsOrderNotFoundException() {
        // given
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .thenThrow(new OrderNotFoundException());

        // when & then
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null)))
                .isInstanceOf(OrderNotFoundException.class);

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentGatewayPort, never()).authorize(any());
    }

    @Test
    @DisplayName("클라이언트 금액 불일치 → PaymentAmountMismatchException (ready INSERT·authorize 미호출)")
    void pay_amountMismatch_throwsPaymentAmountMismatchException() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);

        BigDecimal wrongAmount = BigDecimal.valueOf(9999);

        // when & then
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, wrongAmount)))
                .isInstanceOf(PaymentAmountMismatchException.class);

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentGatewayPort, never()).authorize(any());
    }

    @Test
    @DisplayName("완결성 사전검증 실패 → PaymentEventResolutionException (PG 호출 전 — authorize 미호출)")
    void pay_eventResolutionFailed_throwsPaymentEventResolutionException() {
        // given
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID))
                .thenThrow(new PaymentEventResolutionException());

        // when & then
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null)))
                .isInstanceOf(PaymentEventResolutionException.class);

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentGatewayPort, never()).authorize(any());
    }

    @Test
    @DisplayName("authorize에 idempotencyKey 포함 (#2)")
    void pay_authorize_includesIdempotencyKey() {
        // given
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        Payment savedPayment = buildPayment(ORDER_ID, "ready", AMOUNT);
        when(paymentRepository.saveAndFlush(any())).thenReturn(savedPayment);
        when(paymentGatewayPort.authorize(any())).thenReturn(PaymentAuthorizationResult.approved("MOCK-TX-001"));
        when(orderConfirmation.confirmPaid(anyLong(), anyLong(), any()))
                .thenReturn(new OrderConfirmationResult(
                        ORDER_ID, ORDER_NUMBER, OrderConfirmation.Outcome.CONFIRMED, true, Instant.now(), null));

        // when
        paymentService.pay(USER_ID, ORDER_ID, new PaymentService.PaymentCommand(null, null));

        // then — authorize 호출 시 idempotencyKey 포함
        ArgumentCaptor<PaymentAuthorizationRequest> captor = ArgumentCaptor.forClass(PaymentAuthorizationRequest.class);
        verify(paymentGatewayPort).authorize(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isNotBlank();
    }

    @Test
    @DisplayName("confirmPaid가 REJECTED 반환 시 OrderConfirmationConflictException 되던짐 (롤백 보존)")
    void pay_confirmPaidRejected_rethrowsConflict() {
        // given — PG 승인까지 정상 진행, confirmPaid가 REJECTED 반환
        OrderPaymentView snapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "KRW");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        Payment savedPayment = buildPayment(ORDER_ID, "ready", AMOUNT);
        when(paymentRepository.saveAndFlush(any())).thenReturn(savedPayment);
        when(paymentGatewayPort.authorize(any())).thenReturn(PaymentAuthorizationResult.approved("MOCK-TX-001"));

        String rejectedReason = "주문 상태(cancelled)에서 결제 확정을 할 수 없습니다.";
        OrderConfirmationResult rejectedResult = new OrderConfirmationResult(
                ORDER_ID, ORDER_NUMBER, OrderConfirmation.Outcome.REJECTED, false, Instant.now(), rejectedReason);
        when(orderConfirmation.confirmPaid(ORDER_ID, USER_ID, AMOUNT)).thenReturn(rejectedResult);

        // when & then — REJECTED 수신 시 되던짐으로 409 보존 + 트랜잭션 롤백
        assertThatThrownBy(() -> paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand(null, null)))
                .isInstanceOf(OrderConfirmationConflictException.class)
                .hasMessageContaining(rejectedReason);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Payment buildPayment(long orderId, String status, BigDecimal amount) {
        Payment p = Payment.create(orderId, "mock", amount);
        if ("paid".equals(status)) {
            p.markPaid("MOCK-TX-001", Instant.now());
        }
        return p;
    }
}
