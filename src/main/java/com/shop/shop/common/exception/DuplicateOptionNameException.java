package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 상품 내 옵션명 중복 시 발생하는 예외 (409 Conflict).
 *
 * <p>V1 불변식: 동일 상품 내 옵션명 중복 금지.
 */
public class DuplicateOptionNameException extends BusinessException {

    public DuplicateOptionNameException(long productId, String name) {
        super("이미 사용 중인 옵션명입니다.", HttpStatus.CONFLICT);
    }
}
