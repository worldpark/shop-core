package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 1인 1매 제약 위반 — 동일 쿠폰을 재발급 시도할 때 발생 (409).
 *
 * <p>user_coupons UNIQUE(user_id, coupon_id) 위반에서 파생된다.
 */
public class CouponAlreadyOwnedException extends BusinessException {

    public CouponAlreadyOwnedException() {
        super("이미 보유 중인 쿠폰입니다.", HttpStatus.CONFLICT);
    }

    public CouponAlreadyOwnedException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
