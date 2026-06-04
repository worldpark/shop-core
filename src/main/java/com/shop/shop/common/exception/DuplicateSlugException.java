package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * slug 중복 시 발생하는 예외 (409 Conflict).
 */
public class DuplicateSlugException extends BusinessException {

    public DuplicateSlugException(String slug) {
        super("이미 사용 중인 slug입니다. slug=" + slug, HttpStatus.CONFLICT);
    }
}
