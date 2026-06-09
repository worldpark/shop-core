package com.shop.shop.common.exception;

/**
 * 클라이언트 전달 금액 ≠ 주문 finalAmount 불일치 예외 (400 Bad Request).
 *
 * <p>사용자 입력 오류이므로 400으로 응답한다.
 * error-response-rule: 검증 실패 → 400.
 */
public class PaymentAmountMismatchException extends BusinessException {

    public PaymentAmountMismatchException() {
        super("결제 금액이 주문 금액과 일치하지 않습니다.");
    }

    public PaymentAmountMismatchException(String message) {
        super(message);
    }
}
