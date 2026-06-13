package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 판매자 신청 자격 미달 예외 (409 Conflict).
 *
 * <p>현재 권한이 CONSUMER가 아닌 사용자(SELLER/ADMIN)가 판매자 신청을 시도할 때 발생.
 * 보안 403이 아닌 상태 충돌 409 — "이미 동급 이상이라 신청 행위 자체가 무의미한 상태 충돌"
 * (api-authorization-rule: 자격(eligibility) ≠ 인가(authorization)).
 */
public class SellerApplicationNotEligibleException extends BusinessException {

    public SellerApplicationNotEligibleException() {
        super("판매자 신청 자격이 없습니다(현재 권한 확인).", HttpStatus.CONFLICT);
    }
}
