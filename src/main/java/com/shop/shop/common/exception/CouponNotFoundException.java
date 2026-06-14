package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 쿠폰 미존재 / 소유권 없는 user_coupon 접근 시 발생 (404).
 *
 * <p>존재 은닉 정책: 타인 소유 user_coupon 접근도 이 예외로 반환한다.
 */
public class CouponNotFoundException extends BusinessException {

    public CouponNotFoundException() {
        super("쿠폰을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }

    public CouponNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
