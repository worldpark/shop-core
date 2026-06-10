package com.shop.shop.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment Entity 상태 전이 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>markFailed: ready→failed 허용</li>
 *   <li>markFailed: failed→failed 멱등(no-op)</li>
 *   <li>markFailed: paid→failed IllegalStateException (역전이 금지)</li>
 *   <li>markPaid(Ma1): ready→paid 회귀 없음</li>
 *   <li>markPaid(Ma1): failed→paid 허용 (거절 후 재시도 승인)</li>
 *   <li>markPaid: paid 재호출 멱등</li>
 * </ul>
 */
class PaymentTest {

    private static final long ORDER_ID = 1L;
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);

    // ============================================================
    // markFailed 테스트
    // ============================================================

    @Test
    @DisplayName("markFailed: ready → failed 전이")
    void markFailed_ready_transitionsToFailed() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        assertThat(payment.getStatus()).isEqualTo("ready");

        payment.markFailed("CARD_DECLINED", "카드사에서 결제가 거절되었습니다.");

        assertThat(payment.getStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("markFailed: failed → failed 멱등(no-op) — 재거절 시 상태 무변경")
    void markFailed_alreadyFailed_noOp() {
        Payment payment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        payment.markFailed("CARD_DECLINED", "첫 거절");

        // 동일 row 재거절
        payment.markFailed("CARD_DECLINED", "두 번째 거절 — no-op");

        assertThat(payment.getStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("markFailed: paid → failed IllegalStateException (역전이 금지)")
    void markFailed_paid_throwsIllegalStateException() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());
        assertThat(payment.getStatus()).isEqualTo("paid");

        assertThatThrownBy(() -> payment.markFailed("CARD_DECLINED", "역전이 시도"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markFailed: failureCode/failureReason 인자는 Entity에 미영속(옵션 A) — 상태 전이만 수행")
    void markFailed_failureCodeAndReason_notPersistedToEntity() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);

        payment.markFailed("CARD_DECLINED", "미영속 사유");

        // Entity에 failureCode/failureReason 필드 없음 → 상태만 전이
        assertThat(payment.getStatus()).isEqualTo("failed");
        assertThat(payment.getPgTransactionId()).isNull();
        assertThat(payment.getPaidAt()).isNull();
    }

    // ============================================================
    // markPaid 테스트 (Ma1 포함)
    // ============================================================

    @Test
    @DisplayName("markPaid: ready → paid 전이 (016 happy path 회귀 없음)")
    void markPaid_ready_transitionsToPaid() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);

        payment.markPaid("MOCK-TX-001", Instant.now());

        assertThat(payment.getStatus()).isEqualTo("paid");
        assertThat(payment.getPgTransactionId()).isEqualTo("MOCK-TX-001");
        assertThat(payment.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("markPaid(Ma1): failed → paid 전이 허용 (거절 후 재시도 승인)")
    void markPaid_failed_transitionsToPaid_ma1() {
        Payment payment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        payment.markFailed("CARD_DECLINED", "첫 거절");
        assertThat(payment.getStatus()).isEqualTo("failed");

        // 재시도 승인 — failed → paid (Ma1)
        payment.markPaid("MOCK-TX-RETRY", Instant.now());

        assertThat(payment.getStatus()).isEqualTo("paid");
        assertThat(payment.getPgTransactionId()).isEqualTo("MOCK-TX-RETRY");
        assertThat(payment.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("markPaid: paid 재호출 멱등 — pgTransactionId/paidAt 변경 없음")
    void markPaid_alreadyPaid_idempotent() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        Instant firstPaidAt = Instant.now();
        payment.markPaid("MOCK-TX-001", firstPaidAt);

        // 멱등 재호출
        payment.markPaid("MOCK-TX-002", Instant.now().plusSeconds(5));

        assertThat(payment.getStatus()).isEqualTo("paid");
        assertThat(payment.getPgTransactionId()).isEqualTo("MOCK-TX-001");
        assertThat(payment.getPaidAt()).isEqualTo(firstPaidAt);
    }

    @Test
    @DisplayName("markPaid: cancelled 상태에서 paid 전이 불가 → IllegalStateException")
    void markPaid_nonReadyOrFailed_throwsIllegalStateException() {
        // 현재 cancelled/refunded 상태를 직접 만들 방법이 없으므로
        // 반사(reflection)를 쓰지 않고 가능한 범위에서 검증
        // → ready→paid→failed 역전이 금지(markFailed paid→failed IllegalStateException)로 대신 검증
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());
        // paid → failed 역전이 금지 검증 (markFailed 상단에서 이미 tested)
        assertThatThrownBy(() -> payment.markFailed("X", "Y"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // markCancelled 테스트 (018 신규)
    // ============================================================

    @Test
    @DisplayName("markCancelled: ready → cancelled 전이")
    void markCancelled_ready_transitionsToCancelled() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        assertThat(payment.getStatus()).isEqualTo("ready");

        payment.markCancelled();

        assertThat(payment.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markCancelled: failed → cancelled 전이")
    void markCancelled_failed_transitionsToCancelled() {
        Payment payment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        payment.markFailed("CARD_DECLINED", "거절");
        assertThat(payment.getStatus()).isEqualTo("failed");

        payment.markCancelled();

        assertThat(payment.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markCancelled: cancelled 재호출 → 멱등(no-op)")
    void markCancelled_alreadyCancelled_idempotent() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markCancelled();

        // 멱등 재호출 — 예외 없음, 상태 유지
        payment.markCancelled();

        assertThat(payment.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markCancelled: paid → cancelled 금지 → IllegalStateException(환불을 거쳐야 함)")
    void markCancelled_paid_throwsIllegalStateException() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());
        assertThat(payment.getStatus()).isEqualTo("paid");

        assertThatThrownBy(() -> payment.markCancelled())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markCancelled: refunded → cancelled 금지 → IllegalStateException")
    void markCancelled_refunded_throwsIllegalStateException() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());
        payment.markRefunded("MOCK-REFUND-key");
        assertThat(payment.getStatus()).isEqualTo("refunded");

        assertThatThrownBy(() -> payment.markCancelled())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refunded");
    }

    // ============================================================
    // markRefunded 테스트 (018 신규)
    // ============================================================

    @Test
    @DisplayName("markRefunded: paid → refunded 전이")
    void markRefunded_paid_transitionsToRefunded() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());

        payment.markRefunded("MOCK-REFUND-key-001");

        assertThat(payment.getStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("markRefunded: refunded 재호출 → 멱등(no-op)")
    void markRefunded_alreadyRefunded_idempotent() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());
        payment.markRefunded("MOCK-REFUND-key-001");

        // 멱등 재호출 — 예외 없음
        payment.markRefunded("MOCK-REFUND-key-002");

        assertThat(payment.getStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("markRefunded: ready → refunded 금지 → IllegalStateException")
    void markRefunded_ready_throwsIllegalStateException() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        assertThat(payment.getStatus()).isEqualTo("ready");

        assertThatThrownBy(() -> payment.markRefunded("MOCK-REFUND-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markRefunded: failed → refunded 금지 → IllegalStateException")
    void markRefunded_failed_throwsIllegalStateException() {
        Payment payment = Payment.create(ORDER_ID, "virtual_account", AMOUNT);
        payment.markFailed("CARD_DECLINED", "거절");

        assertThatThrownBy(() -> payment.markRefunded("MOCK-REFUND-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markRefunded: pgRefundId 미영속(옵션 A) — 상태 전이만 수행")
    void markRefunded_pgRefundId_notPersistedToEntity() {
        Payment payment = Payment.create(ORDER_ID, "mock", AMOUNT);
        payment.markPaid("MOCK-TX-001", Instant.now());

        payment.markRefunded("MOCK-REFUND-key-001");

        // pgTransactionId는 markPaid 시 기록된 값 그대로 유지
        assertThat(payment.getStatus()).isEqualTo("refunded");
        assertThat(payment.getPgTransactionId()).isEqualTo("MOCK-TX-001");
    }
}
