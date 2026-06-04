package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 카테고리를 찾을 수 없을 때 발생하는 예외 (404).
 */
public class CategoryNotFoundException extends BusinessException {

    public CategoryNotFoundException(long id) {
        super("카테고리를 찾을 수 없습니다. id=" + id, HttpStatus.NOT_FOUND);
    }
}
