package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 쿠폰 적용 불가 시 발생 (400).
 *
 * <p>최소주문금액 미달, 유효기간 외, 비활성 쿠폰에 적용 시도할 때 발생한다.
 * 주문 트랜잭션을 롤백시킨다.
 */
public class CouponNotApplicableException extends BusinessException {

    public CouponNotApplicableException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
