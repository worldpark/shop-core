package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 취소 상태 충돌 예외 (409 Conflict).
 *
 * <p>이행단계(preparing/shipping/delivered) 주문은 취소가 불가능하다.
 *
 * <p><b>부작용 발생 전 판정(#2, C1 비적용)</b>: PaymentService.cancel 2단계에서
 * locked reader 스냅샷으로 상태를 판정해 PG 환불 호출 전에 던진다.
 * 환불·재고 복원·이벤트 발행이 아직 수행되지 않아 롤백할 부작용이 없다.
 *
 * <p>error-response-rule: 상태 충돌 → 409. BusinessException 계층이 RestExceptionHandler에서
 * 자동으로 ErrorResponse로 매핑된다.
 */
public class OrderCancellationConflictException extends BusinessException {

    public OrderCancellationConflictException() {
        super("주문 상태 충돌로 취소를 처리할 수 없습니다.", HttpStatus.CONFLICT);
    }

    public OrderCancellationConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
