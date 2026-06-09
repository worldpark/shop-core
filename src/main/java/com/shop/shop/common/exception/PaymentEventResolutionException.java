package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 이벤트 완결성 사전검증 실패 예외 (409 Conflict).
 *
 * <p>결제 경로 전용 — PG 호출 전 productId 해석 불가 또는 member 연락처 해석 불가 시 발생.
 * 상태 조회 경로(getPaymentStatus)는 이 예외를 던지지 않는다(#3).
 *
 * <p>내부 상세 정보(어느 variant/productId가 문제인지)는 응답에 노출하지 않는다.
 * error-response-rule: 상태 충돌 → 409.
 */
public class PaymentEventResolutionException extends BusinessException {

    public PaymentEventResolutionException() {
        super("결제 처리를 완료할 수 없는 주문 상태입니다.", HttpStatus.CONFLICT);
    }

    public PaymentEventResolutionException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
