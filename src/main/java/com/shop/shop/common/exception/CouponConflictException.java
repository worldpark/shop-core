package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 쿠폰 동시 사용 경합 패자 / 이미 사용된 쿠폰 사용 시도 시 발생 (409).
 *
 * <p>조건부 UPDATE 영향행 0(markUsedIfUnused 또는 incrementUsedCountIfWithinLimit)에서
 * 발생하며, 주문 트랜잭션을 롤백시킨다.
 */
public class CouponConflictException extends BusinessException {

    public CouponConflictException() {
        super("쿠폰을 사용할 수 없습니다.", HttpStatus.CONFLICT);
    }

    public CouponConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
