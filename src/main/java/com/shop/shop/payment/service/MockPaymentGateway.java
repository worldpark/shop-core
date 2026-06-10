package com.shop.shop.payment.service;

import com.shop.shop.payment.spi.PaymentGatewayPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 모의 PG 구현체 (package-private).
 *
 * <p><b>결정적 거절 규칙(017, 무작위 금지)</b>: 동일 입력 → 동일 결과를 보장한다.
 * 단위 테스트 재현성을 위해 무작위 거절은 절대 금지한다.
 *
 * <p><b>결제수단(method) 기반 결정 규칙</b>:
 * <ul>
 *   <li>{@code method == "virtual_account"} → {@code declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다.")}</li>
 *   <li>그 외 ({@code "mock"}, {@code "card"}, {@code "bank_transfer"} 등) → {@code approved("MOCK-" + UUID)}</li>
 * </ul>
 *
 * <p><b>거절 트리거 method는 {@code payments.method} CHECK 허용값이어야 한다(중요 — DB 제약)</b>:
 * {@code acquireOrResolveReadyRow}는 PG 호출(⑤) 전에 method를 담은 {@code ready} row를 INSERT한다.
 * 따라서 CHECK 밖의 임의 값(예: {@code "test-decline"} 같은 비허용 문자열)을 트리거로 쓰면
 * PG 도달 전 ready INSERT가 CHECK 위반으로 실패해 거절 분기에 진입조차 못 한다.
 * → <b>채택: 트리거 = {@code "virtual_account"}(기존 허용값 재사용, 신규 migration 불필요)</b>.
 * {@code "mock"}/{@code "card"}/{@code "bank_transfer"}는 승인 → 재시도 시 이 값들로 바꾸면
 * {@code failed→paid} 승인이 실 게이트웨이로 도달 가능하다.
 *
 * <p><b>금액 임계 기반 거절을 채택하지 않는 이유(중요)</b>:
 * {@code amount}는 {@code snapshot.finalAmount()}로 주문당 고정이라(같은 주문 재시도 = 같은 금액)
 * 금액 임계로 거절하면 한 번 거절된 주문은 재시도해도 영구 거절되어
 * {@code failed→paid} 재시도 승인 경로(Ma1)가 실 게이트웨이로 도달 불가가 된다.
 * 반면 {@code method}는 클라이언트가 재시도 시 주문을 변경하지 않고 바꿀 수 있는 유일한 입력이므로,
 * "거절 → 결제수단 변경 후 재시도 승인"이 실 게이트웨이로도 재현 가능하다.
 *
 * <p>외부 I/O 없음 — in-process 동기 실행.
 * 실 PG 어댑터로 교체 시 이 컴포넌트만 교체하면 된다(포트 시그니처 무변경).
 */
@Component
class MockPaymentGateway implements PaymentGatewayPort {

    private static final String PG_TX_PREFIX = "MOCK-";

    /**
     * 거절 트리거 결제수단. 이 값이면 항상 거절 반환.
     *
     * <p>{@code payments.method} DB CHECK 허용값({@code 'card'/'bank_transfer'/'virtual_account'/'mock'})
     * 중 하나여야 한다. {@code ready} row가 PG 호출 전에 INSERT되므로, CHECK 밖의 값을 사용하면
     * ready INSERT가 CHECK 위반으로 실패해 거절 분기에 진입 불가가 된다.
     * {@code "virtual_account"}를 재사용한다(신규 migration 불필요).
     *
     * <p>재시도 승인을 원하면 클라이언트가 method를 이 값이 아닌 것으로 변경한다
     * (예: {@code "mock"}, {@code "card"}, {@code "bank_transfer"}).
     */
    static final String DECLINE_METHOD = "virtual_account";

    /** 거절 시 반환할 failureCode (event-catalog 예시 코드). */
    static final String DECLINE_FAILURE_CODE = "CARD_DECLINED";

    /** 거절 시 반환할 failureReason (사용자 노출 가능한 메시지). */
    static final String DECLINE_FAILURE_REASON = "카드사에서 결제가 거절되었습니다.";

    /**
     * {@inheritDoc}
     *
     * <p>결정적 거절 규칙(017):
     * <ul>
     *   <li>{@code method == "virtual_account"} → 거절</li>
     *   <li>그 외 ({@code "mock"}, {@code "card"}, {@code "bank_transfer"} 등) → 승인 ({@code "MOCK-" + UUID})</li>
     * </ul>
     * idempotencyKey는 로그에만 사용(모의는 부작용 없음).
     */
    @Override
    public PaymentAuthorizationResult authorize(PaymentAuthorizationRequest request) {
        if (DECLINE_METHOD.equals(request.method())) {
            return PaymentAuthorizationResult.declined(DECLINE_FAILURE_CODE, DECLINE_FAILURE_REASON);
        }
        String pgTransactionId = PG_TX_PREFIX + UUID.randomUUID();
        return PaymentAuthorizationResult.approved(pgTransactionId);
    }
}
