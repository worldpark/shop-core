package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.markCancelled() / Order.markRefunded() 단위 테스트 (018 신규).
 *
 * <p>검증:
 * <ul>
 *   <li>markCancelled: pending → cancelled 전이 성공</li>
 *   <li>markCancelled: cancelled 재호출 멱등</li>
 *   <li>markCancelled: paid/이행단계에서 호출 시 IllegalStateException</li>
 *   <li>markRefunded: paid → refunded 전이 성공 (#3)</li>
 *   <li>markRefunded: refunded 재호출 멱등</li>
 *   <li>markRefunded: pending/이행단계에서 호출 시 IllegalStateException</li>
 *   <li>016/017 회귀: markPaid pending→paid 그린 유지</li>
 * </ul>
 */
class OrderCancelRefundTest {

    // ============================================================
    // markCancelled 테스트
    // ============================================================

    @Test
    @DisplayName("markCancelled: pending → cancelled 전이")
    void markCancelled_fromPending_success() {
        Order order = createPendingOrder();

        order.markCancelled();

        assertThat(order.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markCancelled: cancelled 재호출 → 멱등(no-op)")
    void markCancelled_alreadyCancelled_idempotent() {
        Order order = createPendingOrder();
        order.markCancelled();

        // 멱등 재호출
        order.markCancelled();

        assertThat(order.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markCancelled: paid → cancelled 금지 → IllegalStateException")
    void markCancelled_fromPaid_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markPaid();
        assertThat(order.getStatus()).isEqualTo("paid");

        assertThatThrownBy(() -> order.markCancelled())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    @Test
    @DisplayName("markCancelled: preparing → cancelled 금지 → IllegalStateException")
    void markCancelled_fromPreparing_throwsIllegalStateException() {
        Order order = createOrderWithStatus("preparing");

        assertThatThrownBy(() -> order.markCancelled())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markCancelled: refunded → cancelled 금지 → IllegalStateException")
    void markCancelled_fromRefunded_throwsIllegalStateException() {
        Order order = createOrderWithStatus("refunded");

        assertThatThrownBy(() -> order.markCancelled())
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // markRefunded 테스트 (#3 — 결제완료 취소 = refunded)
    // ============================================================

    @Test
    @DisplayName("markRefunded: paid → refunded 전이 (#3)")
    void markRefunded_fromPaid_success() {
        Order order = createPendingOrder();
        order.markPaid();
        assertThat(order.getStatus()).isEqualTo("paid");

        order.markRefunded();

        assertThat(order.getStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("markRefunded: refunded 재호출 → 멱등(no-op)")
    void markRefunded_alreadyRefunded_idempotent() {
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();

        // 멱등 재호출
        order.markRefunded();

        assertThat(order.getStatus()).isEqualTo("refunded");
    }

    @Test
    @DisplayName("markRefunded: pending → refunded 금지 → IllegalStateException")
    void markRefunded_fromPending_throwsIllegalStateException() {
        Order order = createPendingOrder();
        assertThat(order.getStatus()).isEqualTo("pending");

        assertThatThrownBy(() -> order.markRefunded())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markRefunded: preparing → refunded 금지 → IllegalStateException")
    void markRefunded_fromPreparing_throwsIllegalStateException() {
        Order order = createOrderWithStatus("preparing");

        assertThatThrownBy(() -> order.markRefunded())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markRefunded: cancelled → refunded 금지 → IllegalStateException")
    void markRefunded_fromCancelled_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markCancelled();
        assertThat(order.getStatus()).isEqualTo("cancelled");

        assertThatThrownBy(() -> order.markRefunded())
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // 016/017 회귀 보장
    // ============================================================

    @Test
    @DisplayName("markPaid: pending → paid 전이 — 016/017 회귀 없음")
    void markPaid_regression_pendingToPaid() {
        Order order = createPendingOrder();

        order.markPaid();

        assertThat(order.getStatus()).isEqualTo("paid");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPendingOrder() {
        return Order.create(1L, "ORD-018-001", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
    }

    private Order createOrderWithStatus(String status) {
        Order order = createPendingOrder();
        try {
            java.lang.reflect.Field statusField = Order.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(order, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return order;
    }
}
