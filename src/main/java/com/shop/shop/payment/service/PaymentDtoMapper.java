package com.shop.shop.payment.service;

import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.dto.PaymentStatusView;
import org.springframework.stereotype.Component;

/**
 * 결제 내부 결과 → DTO 변환기 (package-private).
 *
 * <p>Entity·ownerId를 응답에 노출하지 않는다.
 * ServiceResponse·FacadeImpl이 이 컴포넌트를 사용해 변환한다.
 */
@Component
class PaymentDtoMapper {

    /**
     * PaymentResult → PaymentResponse 변환.
     *
     * @param result  결제 처리 결과
     * @return REST/View 응답 DTO
     */
    PaymentResponse toPaymentResponse(PaymentService.PaymentResult result) {
        var payment = result.payment();
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                result.orderNumber(),
                payment.getStatus(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getPgTransactionId(),
                payment.getPaidAt()
        );
    }

    /**
     * PaymentStatusResult → PaymentStatusView 변환.
     *
     * @param result  결제 상태 조회 결과
     * @return View 결제 상태 DTO
     */
    PaymentStatusView toPaymentStatusView(PaymentService.PaymentStatusResult result) {
        return new PaymentStatusView(
                result.orderId(),
                result.paymentStatus(),
                result.paid(),
                result.payable(),
                result.amount(),
                result.paidAt()
        );
    }

    /**
     * PaymentStatusResult → PaymentResponse 변환 (REST GET용).
     *
     * @param result  결제 상태 조회 결과
     * @return REST 응답 DTO
     */
    PaymentResponse toPaymentResponseFromStatus(PaymentService.PaymentStatusResult result) {
        return new PaymentResponse(
                result.paymentId() != null ? result.paymentId() : 0L,
                result.orderId(),
                result.orderNumber(),
                result.paymentStatus(),
                result.method(),
                result.amount(),
                result.pgTransactionId(),
                result.paidAt()
        );
    }
}
