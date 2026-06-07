package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 상품 구매 불가 예외 (409 Conflict).
 *
 * <p>product.status ≠ ON_SALE 또는 catalog에서 variant가 누락된 경우 발생한다.
 * error-response-rule line 58 — 상태 충돌 → 409.
 */
public class ProductNotPurchasableForOrderException extends BusinessException {

    public ProductNotPurchasableForOrderException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    public ProductNotPurchasableForOrderException() {
        super("구매할 수 없는 상품이 포함되어 있습니다.", HttpStatus.CONFLICT);
    }
}
