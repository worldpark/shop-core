package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 판매자 신청 중복 예외 (409 Conflict).
 *
 * <p>이미 PENDING 상태의 신청이 존재하는 경우 발생.
 * 사전 체크 또는 DB 부분 유니크 인덱스(uq_seller_application_pending) 위반 흡수 시 throw.
 */
public class SellerApplicationDuplicateException extends BusinessException {

    public SellerApplicationDuplicateException() {
        super("이미 심사 중인 신청이 있습니다.", HttpStatus.CONFLICT);
    }
}
