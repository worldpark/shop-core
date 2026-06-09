package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.payment.domain.Payment;
import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.dto.PaymentStatusView;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentFacadeImpl 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>MemberDirectory.findUserIdByEmail 위임 (email → userId)</li>
 *   <li>PaymentService 위임</li>
 *   <li>DTO 변환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeImplTest {

    @Mock private PaymentService paymentService;
    @Mock private MemberDirectory memberDirectory;
    @Mock private PaymentDtoMapper dtoMapper;

    private PaymentFacade paymentFacade;

    private static final String EMAIL = "user@example.com";
    private static final long USER_ID = 42L;
    private static final long ORDER_ID = 100L;

    @BeforeEach
    void setUp() {
        paymentFacade = new PaymentFacadeImpl(paymentService, memberDirectory, dtoMapper);
        when(memberDirectory.findUserIdByEmail(EMAIL)).thenReturn(USER_ID);
    }

    @Test
    @DisplayName("pay: MemberDirectory.findUserIdByEmail 위임 후 PaymentService 호출")
    void pay_delegatesToMemberDirectoryAndPaymentService() {
        Payment payment = Payment.create(ORDER_ID, "mock", BigDecimal.valueOf(10000));
        payment.markPaid("MOCK-TX-001", Instant.now());
        PaymentService.PaymentResult result = new PaymentService.PaymentResult(payment, "ORD-001");
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(result);
        when(dtoMapper.toPaymentResponse(result)).thenReturn(
                new PaymentResponse(1L, ORDER_ID, "ORD-001", "paid", "mock",
                        BigDecimal.valueOf(10000), "MOCK-TX-001", Instant.now()));

        paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest(null, null));

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(paymentService).pay(eq(USER_ID), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("getPaymentStatus: MemberDirectory 위임 후 PaymentService.getPaymentStatus 호출")
    void getPaymentStatus_delegatesToMemberDirectoryAndPaymentService() {
        PaymentService.PaymentStatusResult statusResult = new PaymentService.PaymentStatusResult(
                ORDER_ID, "ORD-001", "paid", true, false,
                BigDecimal.valueOf(10000), Instant.now(), 1L, "mock", "MOCK-TX-001");
        when(paymentService.getPaymentStatus(anyLong(), anyLong())).thenReturn(statusResult);
        when(dtoMapper.toPaymentStatusView(statusResult)).thenReturn(
                new PaymentStatusView(ORDER_ID, "paid", true, false, BigDecimal.valueOf(10000), Instant.now()));

        PaymentStatusView view = paymentFacade.getPaymentStatus(EMAIL, ORDER_ID);

        verify(memberDirectory).findUserIdByEmail(EMAIL);
        verify(paymentService).getPaymentStatus(USER_ID, ORDER_ID);
        assertThat(view).isNotNull();
        assertThat(view.paid()).isTrue();
    }

    @Test
    @DisplayName("반환 타입은 PaymentResponse/PaymentStatusView — Entity 미노출")
    void pay_returnType_notEntity() {
        Payment payment = Payment.create(ORDER_ID, "mock", BigDecimal.valueOf(10000));
        payment.markPaid("MOCK-TX-001", Instant.now());
        PaymentService.PaymentResult result = new PaymentService.PaymentResult(payment, "ORD-001");
        when(paymentService.pay(anyLong(), anyLong(), any())).thenReturn(result);
        PaymentResponse expectedResponse = new PaymentResponse(1L, ORDER_ID, "ORD-001", "paid", "mock",
                BigDecimal.valueOf(10000), "MOCK-TX-001", Instant.now());
        when(dtoMapper.toPaymentResponse(result)).thenReturn(expectedResponse);

        Object response = paymentFacade.pay(EMAIL, ORDER_ID, new PaymentRequest(null, null));

        assertThat(response).isInstanceOf(PaymentResponse.class);
        assertThat(response).isNotInstanceOf(Payment.class);
    }
}
