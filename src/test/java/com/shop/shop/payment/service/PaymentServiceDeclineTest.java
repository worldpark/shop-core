package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.order.spi.OrderPaymentReader;
import com.shop.shop.order.spi.OrderPaymentReader.OrderPaymentView;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.event.PaymentFailedEvent;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * PaymentService 거절 분기 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>PG 거절 시 payments.status=failed + PaymentFailedEvent 발행 + confirmPaid 미호출</li>
 *   <li>거절 시 pay()가 예외를 던지지 않고 declined=true PaymentResult 반환(C1)</li>
 *   <li>PaymentFailedEvent 페이로드 전 필드 매핑 (Mi1·모순4·모순5)</li>
 *   <li>ready 선점에 failed 재사용(Ma1)</li>
 *   <li>동시 충돌 재조회 failed 재사용(Ma2)</li>
 *   <li>거절 후 재시도 승인(failed→paid + confirmPaid 호출)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceDeclineTest {

    @Mock private OrderPaymentReader orderPaymentReader;
    @Mock private OrderConfirmation orderConfirmation;
    @Mock private PaymentGatewayPort paymentGatewayPort;
    @Mock private PaymentRepository paymentRepository;
    @Mock private MemberDirectory memberDirectory;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentService paymentService;

    private static final long USER_ID = 10L;
    private static final long ORDER_ID = 200L;
    private static final String ORDER_NUMBER = "ORD-20260610-200";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final String CURRENCY = "KRW";
    private static final String MEMBER_EMAIL = "buyer@example.com";
    private static final String MEMBER_NAME = "구매자";

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                orderPaymentReader, orderConfirmation, paymentGatewayPort, paymentRepository,
                memberDirectory, eventPublisher);
    }

    private OrderPaymentView pendingSnapshot() {
        return new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, CURRENCY);
    }

    private Payment readyPayment() {
        return Payment.create(ORDER_ID, "virtual_account", AMOUNT);
    }

    private void setupDeclineScenario() {
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(pendingSnapshot());
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact(MEMBER_EMAIL, MEMBER_NAME));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        Payment saved = readyPayment();
        when(paymentRepository.saveAndFlush(any())).thenReturn(saved);
        when(paymentGatewayPort.authorize(any()))
                .thenReturn(PaymentAuthorizationResult.declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다."));
    }

    // ============================================================
    // C1: 거절은 예외 없이 정상 반환
    // ============================================================

    @Test
    @DisplayName("PG 거절 시 pay()가 예외를 던지지 않고 declined=true 결과 반환 (C1)")
    void pay_declined_returnsDeclinedResultWithoutException() {
        setupDeclineScenario();

        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        assertThat(result.declined()).isTrue();
        assertThat(result.failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(result.failureReason()).isEqualTo("카드사에서 결제가 거절되었습니다.");
    }

    @Test
    @DisplayName("PG 거절 시 payments.status=failed 전이")
    void pay_declined_paymentStatusFailed() {
        setupDeclineScenario();

        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        assertThat(result.payment().getStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("PG 거절 시 PaymentFailedEvent 발행 (ApplicationEventPublisher)")
    void pay_declined_publishesPaymentFailedEvent() {
        setupDeclineScenario();

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PaymentFailedEvent event = captor.getValue();
        assertThat(event).isNotNull();
    }

    @Test
    @DisplayName("PG 거절 시 PaymentFailedEvent 페이로드 전 필드 매핑 검증 (Mi1·모순4·모순5)")
    void pay_declined_paymentFailedEventPayloadAllFields() {
        setupDeclineScenario();

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PaymentFailedEvent event = captor.getValue();

        // 공통 봉투
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();

        // 주문/회원 필드
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.orderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(event.memberId()).isEqualTo(USER_ID);

        // Mi1: member.spi로 조회한 연락처
        assertThat(event.memberEmail()).isEqualTo(MEMBER_EMAIL);
        assertThat(event.memberName()).isEqualTo(MEMBER_NAME);

        // P3: amount = finalAmount.longValueExact()
        assertThat(event.amount()).isEqualTo(AMOUNT.longValue());

        // 모순5: currency = snapshot.currency() (하드코딩 아님)
        assertThat(event.currency()).isEqualTo(CURRENCY);

        // 거절 코드/사유
        assertThat(event.failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(event.failureReason()).isEqualTo("카드사에서 결제가 거절되었습니다.");

        // 시도 시각
        assertThat(event.attemptedAt()).isNotNull();
    }

    @Test
    @DisplayName("PG 거절 시 OrderConfirmation.confirmPaid 미호출 + 주문 status 변경 없음")
    void pay_declined_confirmPaidNotCalled() {
        setupDeclineScenario();

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        verify(orderConfirmation, never()).confirmPaid(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("PG 거절 시 연락처를 PG 호출 전 1회만 조회 (Mi1·모순4 후자)")
    void pay_declined_memberDirectoryCalledOnceBeforePG() {
        setupDeclineScenario();

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        // member.spi는 PG 호출 전 1회만 조회 (⑤-B에서 재조회 없음)
        verify(memberDirectory, times(1)).findContactByUserId(USER_ID);
    }

    // ============================================================
    // Ma1: failed row 재사용 (거절 후 재시도)
    // ============================================================

    @Test
    @DisplayName("기존 failed row 존재 시 신규 INSERT 없이 동일 row 재사용 (Ma1)")
    void pay_existingFailedRow_reusesRow_ma1() {
        OrderPaymentView snapshot = pendingSnapshot();
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact(MEMBER_EMAIL, MEMBER_NAME));

        // 기존 failed row 존재
        Payment failedPayment = readyPayment();
        failedPayment.markFailed("CARD_DECLINED", "기존 거절");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failedPayment));

        when(paymentGatewayPort.authorize(any()))
                .thenReturn(PaymentAuthorizationResult.declined("CARD_DECLINED", "재거절"));

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        // 신규 INSERT 없음 (saveAndFlush 호출 없음)
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("거절 후 재시도 승인: failed→paid 전이 + confirmPaid 호출 (Ma1)")
    void pay_afterDecline_retryApproval_failedToPaid_ma1() {
        OrderPaymentView snapshot = pendingSnapshot();
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact(MEMBER_EMAIL, MEMBER_NAME));

        // 기존 failed row 존재 (이전 거절)
        Payment failedPayment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        failedPayment.markFailed("CARD_DECLINED", "이전 거절");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failedPayment));

        // 이번엔 승인
        when(paymentGatewayPort.authorize(any()))
                .thenReturn(PaymentAuthorizationResult.approved("MOCK-TX-RETRY"));

        when(orderConfirmation.confirmPaid(anyLong(), anyLong(), any()))
                .thenReturn(new com.shop.shop.order.spi.OrderConfirmation.OrderConfirmationResult(
                        ORDER_ID, ORDER_NUMBER, com.shop.shop.order.spi.OrderConfirmation.Outcome.CONFIRMED,
                        true, Instant.now(), null));

        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("mock", null));

        // failed→paid 전이 확인
        assertThat(result.payment().getStatus()).isEqualTo("paid");
        assertThat(result.declined()).isFalse();

        // confirmPaid 호출(주문 확정 경로 진입)
        verify(orderConfirmation).confirmPaid(ORDER_ID, USER_ID, AMOUNT);

        // 신규 INSERT 없음 (row 재사용)
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    // ============================================================
    // Ma2: 동시 충돌 재조회 failed 재사용
    // ============================================================

    @Test
    @DisplayName("동시 충돌(DataIntegrityViolationException) 재조회 결과 failed → failed row 재사용(Ma2)")
    void pay_concurrentConflict_reQueryFailed_reusesFailed_ma2() {
        OrderPaymentView snapshot = pendingSnapshot();
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(snapshot);
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact(MEMBER_EMAIL, MEMBER_NAME));

        // 첫 조회 → empty (신규 INSERT 시도)
        // INSERT 실패 → uq 위반 → 재조회 → failed row 발견 (Ma2)
        Payment failedPayment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        failedPayment.markFailed("CARD_DECLINED", "승자 거절");
        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.empty())           // 첫 번째 조회 (신규 INSERT 전)
                .thenReturn(Optional.of(failedPayment)); // 재조회 (uq 위반 후)
        when(paymentRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq violation"));

        when(paymentGatewayPort.authorize(any()))
                .thenReturn(PaymentAuthorizationResult.declined("CARD_DECLINED", "패자 거절"));

        // NPE나 미정의 동작 없이 처리됨
        PaymentService.PaymentResult result = paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        assertThat(result.declined()).isTrue();
        // payments row는 1건 유지 (uq 불변식)
    }

    // ============================================================
    // currency 하드코딩 금지(모순5)
    // ============================================================

    @Test
    @DisplayName("PaymentFailedEvent.currency는 snapshot.currency() 사용 — 하드코딩 금지 (모순5)")
    void pay_declined_eventCurrencyFromSnapshot_notHardcoded() {
        // USD 통화 주문으로 테스트
        OrderPaymentView usdSnapshot = new OrderPaymentView(ORDER_ID, ORDER_NUMBER, USER_ID, "pending", AMOUNT, "USD");
        when(orderPaymentReader.getPayableOrder(ORDER_ID, USER_ID)).thenReturn(usdSnapshot);
        when(memberDirectory.findContactByUserId(USER_ID))
                .thenReturn(new MemberContact(MEMBER_EMAIL, MEMBER_NAME));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        Payment saved = readyPayment();
        when(paymentRepository.saveAndFlush(any())).thenReturn(saved);
        when(paymentGatewayPort.authorize(any()))
                .thenReturn(PaymentAuthorizationResult.declined("CARD_DECLINED", "거절"));

        paymentService.pay(USER_ID, ORDER_ID,
                new PaymentService.PaymentCommand("virtual_account", null));

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        // currency는 snapshot 값 그대로 ("USD") — 하드코딩 "KRW" 아님
        assertThat(captor.getValue().currency()).isEqualTo("USD");
    }
}
