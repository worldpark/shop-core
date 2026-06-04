package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 상품을 찾을 수 없을 때 발생하는 예외 (404).
 */
public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(long id) {
        super("상품을 찾을 수 없습니다. id=" + id, HttpStatus.NOT_FOUND);
    }
}
