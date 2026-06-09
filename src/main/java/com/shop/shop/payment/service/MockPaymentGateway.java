package com.shop.shop.payment.service;

import com.shop.shop.payment.spi.PaymentGatewayPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 모의 PG 구현체 (package-private).
 *
 * <p>016: 항상 승인을 반환한다. 거절 분기는 017에서 추가.
 * pgTransactionId는 "MOCK-" + UUID 형식으로 결정적/식별 가능하게 생성한다.
 *
 * <p>외부 I/O 없음 — in-process 동기 실행.
 * 실 PG 어댑터로 교체 시 이 컴포넌트를 교체하면 된다(ObjectStorage 추상화 철학).
 */
@Component
class MockPaymentGateway implements PaymentGatewayPort {

    private static final String PG_TX_PREFIX = "MOCK-";

    /**
     * {@inheritDoc}
     *
     * <p>016 모의 구현: 항상 승인.
     * pgTransactionId = "MOCK-" + UUID (결정적/식별 가능 형식).
     * idempotencyKey는 로그에만 사용(모의는 부작용 없음).
     */
    @Override
    public PaymentAuthorizationResult authorize(PaymentAuthorizationRequest request) {
        String pgTransactionId = PG_TX_PREFIX + UUID.randomUUID();
        return PaymentAuthorizationResult.approved(pgTransactionId);
    }
}
