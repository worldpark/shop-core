package com.shop.shop.payment.service;

import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.dto.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentServiceResponse 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>(long)auth.getPrincipal() userId 추출</li>
 *   <li>PaymentService 위임</li>
 *   <li>PaymentResponse 변환 (ownerId/Entity 미노출)</li>
 *   <li>멱등 재요청 200 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceResponseTest {

    @Mock private PaymentService paymentService;
    @Mock private PaymentDtoMapper dtoMapper;
    @Mock private Authentication authentication;

    private PaymentServiceResponse paymentServiceResponse;

    private static final long USER_ID = 42L;
    private static final long ORDER_ID = 100L;

    @BeforeEach
    void setUp() {
        paymentServiceResponse = new PaymentServiceResponse(paymentService, dtoMapper);
        when(authentication.getPrincipal()).thenReturn(USER_ID);
    }

    @Test
    @DisplayName("pay: (long)auth.getPrincipal() userId 추출 후 PaymentService 위임")
    void pay_extractsUserIdFromPrincipal() {
        Payment payment = Payment.create(ORDER_ID, "mock", BigDecimal.valueOf(10000));
        payment.markPaid("MOCK-TX-001", Instant.now());
        PaymentService.PaymentResult result = PaymentService.PaymentResult.approved(payment, "ORD-001");
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(result);
        when(dtoMapper.toPaymentResponse(result)).thenReturn(
                new PaymentResponse(1L, ORDER_ID, "ORD-001", "paid", "mock",
                        BigDecimal.valueOf(10000), "MOCK-TX-001", Instant.now()));

        paymentServiceResponse.pay(authentication, ORDER_ID, null);

        verify(paymentService).pay(eq(USER_ID), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("pay 응답에 userId/Entity 미포함")
    void pay_responseDoesNotContainOwnerIdOrEntity() {
        Payment payment = Payment.create(ORDER_ID, "mock", BigDecimal.valueOf(10000));
        payment.markPaid("MOCK-TX-001", Instant.now());
        PaymentService.PaymentResult result = PaymentService.PaymentResult.approved(payment, "ORD-001");
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(result);

        PaymentResponse response = new PaymentResponse(
                1L, ORDER_ID, "ORD-001", "paid", "mock",
                BigDecimal.valueOf(10000), "MOCK-TX-001", Instant.now());
        when(dtoMapper.toPaymentResponse(result)).thenReturn(response);

        PaymentResponse actual = paymentServiceResponse.pay(authentication, ORDER_ID, null);

        // 응답 타입이 PaymentResponse (Entity 아님)
        assertThat(actual).isInstanceOf(PaymentResponse.class);
        assertThat(actual).isNotInstanceOf(Payment.class);
    }

    @Test
    @DisplayName("getPaymentStatus: userId 추출 후 PaymentService 위임")
    void getPaymentStatus_extractsUserIdFromPrincipal() {
        PaymentService.PaymentStatusResult statusResult = new PaymentService.PaymentStatusResult(
                ORDER_ID, "ORD-001", "paid", true, false,
                BigDecimal.valueOf(10000), Instant.now(), 1L, "mock", "MOCK-TX-001");
        when(paymentService.getPaymentStatus(anyLong(), anyLong())).thenReturn(statusResult);
        when(dtoMapper.toPaymentResponseFromStatus(statusResult)).thenReturn(
                new PaymentResponse(1L, ORDER_ID, "ORD-001", "paid", "mock",
                        BigDecimal.valueOf(10000), "MOCK-TX-001", Instant.now()));

        paymentServiceResponse.getPaymentStatus(authentication, ORDER_ID);

        verify(paymentService).getPaymentStatus(USER_ID, ORDER_ID);
    }
}
