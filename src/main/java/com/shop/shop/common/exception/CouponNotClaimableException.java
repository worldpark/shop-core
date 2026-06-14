package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 발급 불가 쿠폰 접근 시 발생 (400).
 *
 * <p>비활성 쿠폰, 유효기간 외 쿠폰 발급 시도.
 */
public class CouponNotClaimableException extends BusinessException {

    public CouponNotClaimableException() {
        super("발급 기간이 아니거나 비활성 쿠폰입니다.", HttpStatus.BAD_REQUEST);
    }

    public CouponNotClaimableException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
