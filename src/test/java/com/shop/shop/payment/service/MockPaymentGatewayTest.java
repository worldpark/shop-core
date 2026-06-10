package com.shop.shop.payment.service;

import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationRequest;
import com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockPaymentGateway 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>항상 승인 반환 (method != "virtual_account")</li>
 *   <li>pgTransactionId 비어있지 않음</li>
 *   <li>pgTransactionId가 "MOCK-" 접두사로 시작</li>
 *   <li>failureCode/failureReason null (승인 시)</li>
 *   <li>결정적 거절 규칙: method="virtual_account" → declined (무작위 아님)</li>
 *   <li>거절 시 approved=false + failureCode/failureReason non-null</li>
 *   <li>거절 failureCode가 event-catalog 코드 집합에 포함</li>
 *   <li>동일 입력으로 두 번 호출 → 동일 결과 (결정성)</li>
 * </ul>
 */
class MockPaymentGatewayTest {

    private static final Set<String> VALID_FAILURE_CODES =
            Set.of("INSUFFICIENT_FUNDS", "LIMIT_EXCEEDED", "CARD_DECLINED");

    private PaymentGatewayPort gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway();
    }

    // ============================================================
    // 승인 경로 (016 기존 케이스 유지)
    // ============================================================

    @Test
    @DisplayName("항상 승인 — approved=true (method != virtual_account)")
    void authorize_alwaysApproved_whenMethodNotDecline() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("mock"));

        assertThat(result.approved()).isTrue();
    }

    @Test
    @DisplayName("pgTransactionId 비어있지 않음")
    void authorize_pgTransactionIdNotBlank() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("mock"));

        assertThat(result.pgTransactionId()).isNotBlank();
    }

    @Test
    @DisplayName("pgTransactionId가 MOCK- 접두사로 시작")
    void authorize_pgTransactionIdStartsWithMock() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("mock"));

        assertThat(result.pgTransactionId()).startsWith("MOCK-");
    }

    @Test
    @DisplayName("승인 시 failureCode/failureReason null")
    void authorize_noFailureFields_onApproval() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("mock"));

        assertThat(result.failureCode()).isNull();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("동일 입력으로 두 번 호출하면 모두 approved — 결정적 승인")
    void authorize_calledTwice_bothApproved() {
        PaymentAuthorizationRequest request = buildRequest("mock");

        PaymentAuthorizationResult r1 = gateway.authorize(request);
        PaymentAuthorizationResult r2 = gateway.authorize(request);

        assertThat(r1.approved()).isTrue();
        assertThat(r2.approved()).isTrue();
    }

    @Test
    @DisplayName("두 번 호출 시 pgTransactionId는 서로 다름 (UUID 기반)")
    void authorize_calledTwice_differentPgTransactionId() {
        PaymentAuthorizationRequest request = buildRequest("mock");

        String tx1 = gateway.authorize(request).pgTransactionId();
        String tx2 = gateway.authorize(request).pgTransactionId();

        assertThat(tx1).isNotEqualTo(tx2);
    }

    @Test
    @DisplayName("card/bank_transfer/mock method는 승인 (016 허용값)")
    void authorize_approvedMethods_card_bankTransfer_mock() {
        assertThat(gateway.authorize(buildRequest("card")).approved()).isTrue();
        assertThat(gateway.authorize(buildRequest("bank_transfer")).approved()).isTrue();
        assertThat(gateway.authorize(buildRequest("mock")).approved()).isTrue();
    }

    // ============================================================
    // 거절 경로 (017 신규 케이스)
    // ============================================================

    @Test
    @DisplayName("결정적 거절 규칙: method=virtual_account → declined (무작위 아님)")
    void authorize_virtualAccountMethod_returnsDeclined() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("virtual_account"));

        assertThat(result.approved()).isFalse();
    }

    @Test
    @DisplayName("거절 시 pgTransactionId null")
    void authorize_declined_pgTransactionIdNull() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("virtual_account"));

        assertThat(result.pgTransactionId()).isNull();
    }

    @Test
    @DisplayName("거절 시 failureCode non-null + event-catalog 코드 집합에 포함")
    void authorize_declined_failureCodeInCatalog() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("virtual_account"));

        assertThat(result.failureCode()).isNotNull();
        assertThat(VALID_FAILURE_CODES).contains(result.failureCode());
    }

    @Test
    @DisplayName("거절 시 failureReason non-null (사용자 노출 메시지)")
    void authorize_declined_failureReasonNotNull() {
        PaymentAuthorizationResult result = gateway.authorize(buildRequest("virtual_account"));

        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("동일 입력(virtual_account) 두 번 호출 → 두 번 모두 declined (결정성 보장, 무작위 아님)")
    void authorize_virtualAccount_calledTwice_bothDeclined() {
        PaymentAuthorizationRequest request = buildRequest("virtual_account");

        PaymentAuthorizationResult r1 = gateway.authorize(request);
        PaymentAuthorizationResult r2 = gateway.authorize(request);

        assertThat(r1.approved()).isFalse();
        assertThat(r2.approved()).isFalse();
        // 같은 failureCode (결정적 규칙)
        assertThat(r1.failureCode()).isEqualTo(r2.failureCode());
    }

    @Test
    @DisplayName("virtual_account 이외 method(null/빈 문자열/card 등)는 승인")
    void authorize_nonDeclineMethod_approved() {
        assertThat(gateway.authorize(buildRequest("card")).approved()).isTrue();
        assertThat(gateway.authorize(buildRequest("bank_transfer")).approved()).isTrue();
        assertThat(gateway.authorize(buildRequest(null)).approved()).isTrue();
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private PaymentAuthorizationRequest buildRequest(String method) {
        return new PaymentAuthorizationRequest(
                "ORD-20260610-001",
                BigDecimal.valueOf(10000),
                "KRW",
                method,
                "idempotency-key-001"
        );
    }
}
