package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 판매자 신청 상태 충돌 예외 (409 Conflict).
 *
 * <p>발생 시나리오:
 * <ul>
 *   <li>비-PENDING(APPROVED/REJECTED) 신청 승인/반려 시도 — 터미널 상태 재심사</li>
 *   <li>승인 시점 신청자가 이미 SELLER/ADMIN인 경우 — admin 토글 레이스</li>
 * </ul>
 */
public class SellerApplicationStateConflictException extends BusinessException {

    public SellerApplicationStateConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
