package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 결제 거절 예외 (402 Payment Required).
 *
 * <p>PG가 결제를 거절했을 때 ServiceResponse/FacadeImpl 계층(트랜잭션 밖)에서만 throw한다(C1).
 *
 * <p><b>트랜잭션 주의(C1)</b>: 이 예외를 {@code @Transactional} 메서드 안에서 던지면
 * RuntimeException 롤백으로 {@code payments.status=failed} 기록과 {@code PaymentFailedEvent}
 * Outbox 저장이 함께 롤백된다 — Acceptance 위반. 반드시 {@code PaymentService.pay}가
 * 정상 반환(커밋)된 이후 ServiceResponse/FacadeImpl에서 throw해야 한다.
 *
 * <p>메시지에는 사용자에게 노출 가능한 {@code failureReason}만 담는다.
 * 내부 PG 원문·{@code failureCode}·스택트레이스는 포함하지 않는다(error-response-rule).
 *
 * <p>{@code RestExceptionHandler}의 기존 {@code BusinessException} 핸들러가
 * {@code e.getStatus()}(402)를 그대로 매핑 → 신규 핸들러 불필요(Ma3).
 */
public class PaymentDeclinedException extends BusinessException {

    /**
     * @param failureReason 사용자 노출 가능한 거절 사유 메시지 (내부 PG 원문·failureCode 미포함)
     */
    public PaymentDeclinedException(String failureReason) {
        super(failureReason, HttpStatus.PAYMENT_REQUIRED);
    }
}
