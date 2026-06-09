package com.shop.shop.payment.service;

import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockPaymentGateway 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>항상 승인 반환</li>
 *   <li>pgTransactionId 비어있지 않음</li>
 *   <li>pgTransactionId가 "MOCK-" 접두사로 시작</li>
 *   <li>failureCode/failureReason null (승인 시)</li>
 * </ul>
 */
class MockPaymentGatewayTest {

    private PaymentGatewayPort gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway();
    }

    @Test
    @DisplayName("항상 승인 — approved=true")
    void authorize_alwaysApproved() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest());

        assertThat(result.approved()).isTrue();
    }

    @Test
    @DisplayName("pgTransactionId 비어있지 않음")
    void authorize_pgTransactionIdNotBlank() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest());

        assertThat(result.pgTransactionId()).isNotBlank();
    }

    @Test
    @DisplayName("pgTransactionId가 MOCK- 접두사로 시작")
    void authorize_pgTransactionIdStartsWithMock() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest());

        assertThat(result.pgTransactionId()).startsWith("MOCK-");
    }

    @Test
    @DisplayName("승인 시 failureCode/failureReason null")
    void authorize_noFailureFields_onApproval() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest());

        assertThat(result.failureCode()).isNull();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("동일 입력으로 두 번 호출하면 모두 approved — 결정적 승인")
    void authorize_calledTwice_bothApproved() {
        PaymentAuthorizationRequest request = buildRequest();

        PaymentAuthorizationResult r1 = gateway.authorize(request);
        PaymentAuthorizationResult r2 = gateway.authorize(request);

        assertThat(r1.approved()).isTrue();
        assertThat(r2.approved()).isTrue();
    }

    @Test
    @DisplayName("두 번 호출 시 pgTransactionId는 서로 다름 (UUID 기반)")
    void authorize_calledTwice_differentPgTransactionId() {
        PaymentAuthorizationRequest request = buildRequest();

        String tx1 = gateway.authorize(request).pgTransactionId();
        String tx2 = gateway.authorize(request).pgTransactionId();

        assertThat(tx1).isNotEqualTo(tx2);
    }

    private PaymentAuthorizationRequest buildRequest() {
        return new PaymentAuthorizationRequest(
                "ORD-20260608-001",
                BigDecimal.valueOf(10000),
                "KRW",
                "mock",
                "idempotency-key-001"
        );
    }
}
