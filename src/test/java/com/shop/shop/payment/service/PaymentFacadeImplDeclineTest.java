package com.shop.shop.payment.service;

import com.shop.shop.common.exception.PaymentDeclinedException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.spi.PaymentFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * PaymentFacadeImpl 거절 매핑 단위 테스트 (C1·Ma3).
 *
 * <p>검증:
 * <ul>
 *   <li>거절 결과(declined=true) → PaymentDeclinedException(402, failureReason) throw</li>
 *   <li>승인 결과(declined=false) → PaymentResponse(200) 정상 반환</li>
 *   <li>거절 예외 메시지 = failureReason (내부 failureCode 미포함)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeImplDeclineTest {

    @Mock private PaymentService paymentService;
    @Mock private MemberDirectory memberDirectory;
    @Mock private PaymentDtoMapper dtoMapper;

    private PaymentFacade paymentFacade;

    private static final String EMAIL = "buyer@example.com";
    private static final long USER_ID = 10L;
    private static final long ORDER_ID = 200L;
    private static final String ORDER_NUMBER = "ORD-20260610-200";
    private static final String FAILURE_REASON = "카드사에서 결제가 거절되었습니다.";
    private static final String FAILURE_CODE = "CARD_DECLINED";

    @BeforeEach
    void setUp() {
        paymentFacade = new PaymentFacadeImpl(paymentService, memberDirectory, dtoMapper);
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
    }

    @Test
    @DisplayName("거절 결과(declined=true) → PaymentDeclinedException(402) throw (C1·Ma3)")
    void pay_declined_throwsPaymentDeclinedException() {
        Payment failedPayment = buildFailedPayment();
        PaymentService.PaymentResult declinedResult =
                PaymentService.PaymentResult.declined(failedPayment, ORDER_NUMBER, FAILURE_CODE, FAILURE_REASON);
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(declinedResult);

        assertThatThrownBy(() -> paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest("virtual_account", null)))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining(FAILURE_REASON);
    }

    @Test
    @DisplayName("거절 예외 상태 코드 = 402 Payment Required")
    void pay_declined_exceptionStatus402() {
        Payment failedPayment = buildFailedPayment();
        PaymentService.PaymentResult declinedResult =
                PaymentService.PaymentResult.declined(failedPayment, ORDER_NUMBER, FAILURE_CODE, FAILURE_REASON);
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(declinedResult);

        assertThatThrownBy(() -> paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest("virtual_account", null)))
                .isInstanceOf(PaymentDeclinedException.class)
                .satisfies(ex -> {
                    PaymentDeclinedException pde = (PaymentDeclinedException) ex;
                    assertThat(pde.getStatus().value()).isEqualTo(402);
                });
    }

    @Test
    @DisplayName("거절 예외 메시지에 failureCode 미포함 (내부 코드 비노출)")
    void pay_declined_exceptionMessageDoesNotContainFailureCode() {
        Payment failedPayment = buildFailedPayment();
        PaymentService.PaymentResult declinedResult =
                PaymentService.PaymentResult.declined(failedPayment, ORDER_NUMBER, FAILURE_CODE, FAILURE_REASON);
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(declinedResult);

        assertThatThrownBy(() -> paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest("virtual_account", null)))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageNotContaining(FAILURE_CODE);
    }

    @Test
    @DisplayName("승인 결과(declined=false) → PaymentResponse(200) 정상 반환 (016 회귀 없음)")
    void pay_approved_returnsPaymentResponse200() {
        Payment paidPayment = Payment.create(ORDER_ID, "mock", BigDecimal.valueOf(50000));
        paidPayment.markPaid("MOCK-TX-001", Instant.now());
        PaymentService.PaymentResult approvedResult =
                PaymentService.PaymentResult.approved(paidPayment, ORDER_NUMBER);
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(approvedResult);

        PaymentResponse expectedResponse = new PaymentResponse(
                1L, ORDER_ID, ORDER_NUMBER, "paid", "mock",
                BigDecimal.valueOf(50000), "MOCK-TX-001", Instant.now());
        when(dtoMapper.toPaymentResponse(approvedResult)).thenReturn(expectedResponse);

        PaymentResponse response = paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest(null, null));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("paid");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Payment buildFailedPayment() {
        Payment p = Payment.create(ORDER_ID, "virtual_account", BigDecimal.valueOf(50000));
        p.markFailed(FAILURE_CODE, FAILURE_REASON);
        return p;
    }
}
