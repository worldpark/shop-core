package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * variant를 찾을 수 없을 때 발생하는 예외 (404 Not Found).
 *
 * <p>V11 불변식: variantId가 해당 productId 하위 리소스가 아닌 경우 포함.
 */
public class VariantNotFoundException extends BusinessException {

    public VariantNotFoundException(long id) {
        super("variant를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
