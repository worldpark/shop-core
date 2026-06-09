package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 결제 처리 중 예외 (409 Conflict).
 *
 * <p>동일 주문에 대한 동시 결제 요청 경합 시:
 * ready row 선점 INSERT가 uq_payments_order_id로 직렬화되어
 * DataIntegrityViolationException 발생 후 재조회 시 ready row가 남아있으면 던진다(#2).
 *
 * <p>내부 정보(paymentId/orderId 등)는 응답에 노출하지 않는다.
 * error-response-rule: 상태 충돌 → 409.
 */
public class PaymentInProgressException extends BusinessException {

    public PaymentInProgressException() {
        super("결제 처리 중입니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT);
    }

    public PaymentInProgressException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
