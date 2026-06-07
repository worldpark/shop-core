package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 재고 부족·variant 비활성·variant 미존재(주문 시점) 예외 (409 Conflict).
 *
 * <p>error-response-rule line 58 — "상태 충돌(재고 부족 등) → 409".
 * 락 전 사전검증·락 후 권위 검증 모두 이 예외를 사용한다.
 * 메시지에 정확한 재고 수치를 노출하지 않는다.
 */
public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    public InsufficientStockException() {
        super("재고가 부족합니다.", HttpStatus.CONFLICT);
    }
}
