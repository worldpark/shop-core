package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 판매자 신청 없음 예외 (404 Not Found).
 *
 * <p>발생 시나리오:
 * <ul>
 *   <li>REST GET /api/v1/seller-applications/me — 이력 없는 경우</li>
 *   <li>admin 승인/반려 시 존재하지 않는 applicationId</li>
 * </ul>
 * View /seller-applications/me는 이 예외를 throw하지 않고 Optional(안내 화면)로 처리한다.
 */
public class SellerApplicationNotFoundException extends BusinessException {

    public SellerApplicationNotFoundException() {
        super("판매자 신청을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
