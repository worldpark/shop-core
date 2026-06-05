package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 옵션 내 옵션값 중복 시 발생하는 예외 (409 Conflict).
 *
 * <p>V2 불변식: 동일 옵션 내 옵션값 중복 금지.
 */
public class DuplicateOptionValueException extends BusinessException {

    public DuplicateOptionValueException(long optionId, String value) {
        super("이미 사용 중인 옵션값입니다.", HttpStatus.CONFLICT);
    }
}
