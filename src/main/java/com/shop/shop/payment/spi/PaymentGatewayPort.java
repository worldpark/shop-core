package com.shop.shop.payment.spi;

import java.math.BigDecimal;

/**
 * 모의 PG(결제 게이트웨이) 추상화 published port.
 *
 * <p>실 PG 어댑터로 교체 가능하도록 인터페이스를 {@code payment/spi}에 두고
 * 모의 구현({@link com.shop.shop.payment.service.MockPaymentGateway})을 {@code payment/service}에 분리한다
 * (ObjectStorage 추상화 철학과 동일).
 *
 * <p>016 모의 구현은 항상 승인한다. 거절 분기는 017에서 추가.
 * 포트 시그니처는 승인/거절을 모두 표현할 수 있도록 설계해 017에서 인터페이스를 바꾸지 않는다.
 *
 * <p>{@code idempotencyKey}는 결제 식별자(orderNumber 또는 paymentId) — 실 PG 전환 시
 * 프로세스 재시도까지 at-most-once charge를 보장한다(#2). 016 모의는 무시해도 무방하나
 * 시그니처에 포함해 017/실 PG에서 불변.
 */
public interface PaymentGatewayPort {

    /**
     * 결제 승인 요청.
     *
     * @param request 승인 요청 (주문번호·금액·통화·method·idempotencyKey 포함)
     * @return 승인 결과 (approved 또는 declined)
     */
    PaymentAuthorizationResult authorize(PaymentAuthorizationRequest request);

    /**
     * 결제 승인 요청 DTO.
     *
     * @param orderNumber    주문 번호
     * @param amount         결제 금액 (BigDecimal)
     * @param currency       통화 코드 (예: "KRW")
     * @param method         결제 수단 (예: "mock")
     * @param idempotencyKey 멱등성 키 (orderNumber 또는 paymentId) — 실 PG at-most-once 보장용
     */
    record PaymentAuthorizationRequest(
            String orderNumber,
            BigDecimal amount,
            String currency,
            String method,
            String idempotencyKey
    ) {}

    /**
     * 결제 승인 결과 DTO.
     *
     * <p>정적 팩토리:
     * <ul>
     *   <li>{@link #approved(String)} — 승인 성공</li>
     *   <li>{@link #declined(String, String)} — 거절 (017에서 활성)</li>
     * </ul>
     *
     * @param approved        승인 성공 여부
     * @param pgTransactionId PG 거래 번호 (승인 시 non-null)
     * @param failureCode     실패 코드 (거절 시 non-null)
     * @param failureReason   실패 사유 (거절 시 non-null)
     */
    record PaymentAuthorizationResult(
            boolean approved,
            String pgTransactionId,
            String failureCode,
            String failureReason
    ) {

        /**
         * 승인 결과 팩토리.
         *
         * @param pgTransactionId PG 거래 번호
         * @return 승인 결과
         */
        public static PaymentAuthorizationResult approved(String pgTransactionId) {
            return new PaymentAuthorizationResult(true, pgTransactionId, null, null);
        }

        /**
         * 거절 결과 팩토리 (017에서 활성).
         *
         * @param failureCode   실패 코드
         * @param failureReason 실패 사유
         * @return 거절 결과
         */
        public static PaymentAuthorizationResult declined(String failureCode, String failureReason) {
            return new PaymentAuthorizationResult(false, null, failureCode, failureReason);
        }
    }
}
