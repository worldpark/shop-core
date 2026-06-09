package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 상태 전이 충돌 예외 (409 Conflict).
 *
 * <p>주문 status가 pending/paid 외의 비정상 상태이거나 금액 불일치로 확정이 불가능한 경우.
 * error-response-rule: 상태 충돌 → 409.
 */
public class OrderConfirmationConflictException extends BusinessException {

    public OrderConfirmationConflictException() {
        super("주문 상태 충돌로 결제 확정에 실패했습니다.", HttpStatus.CONFLICT);
    }

    public OrderConfirmationConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
