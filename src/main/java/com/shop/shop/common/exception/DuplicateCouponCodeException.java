package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 쿠폰 코드 중복 — admin 쿠폰 정의 생성 시 발생 (409).
 *
 * <p>coupons UNIQUE(code) 위반에서 파생된다.
 */
public class DuplicateCouponCodeException extends BusinessException {

    public DuplicateCouponCodeException() {
        super("이미 존재하는 쿠폰 코드입니다.", HttpStatus.CONFLICT);
    }

    public DuplicateCouponCodeException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
