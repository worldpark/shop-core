package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.markShipping() 단위 테스트 (Task 020).
 *
 * <p>검증:
 * <ul>
 *   <li>markShipping: preparing → shipping 전이 성공 (첫 배송 시작 rollup)</li>
 *   <li>markShipping: 그 외 상태(pending/paid/shipping/delivered/cancelled/refunded) → IllegalStateException</li>
 * </ul>
 */
class OrderMarkShippingTest {

    // ============================================================
    // 성공 — preparing → shipping
    // ============================================================

    @Test
    @DisplayName("markShipping: preparing → shipping 전이 성공")
    void markShipping_fromPreparing_success() {
        Order order = createOrderWithStatus("preparing");

        order.markShipping();

        assertThat(order.getStatus()).isEqualTo("shipping");
    }

    // ============================================================
    // 실패 — preparing 이외 상태
    // ============================================================

    @Test
    @DisplayName("markShipping: pending 상태 → IllegalStateException")
    void markShipping_fromPending_throwsIllegalStateException() {
        Order order = createPendingOrder();

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preparing");
    }

    @Test
    @DisplayName("markShipping: paid 상태 → IllegalStateException")
    void markShipping_fromPaid_throwsIllegalStateException() {
        Order order = createOrderWithStatus("paid");

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markShipping: 이미 shipping → IllegalStateException (멱등 없음, 서비스가 skip 처리)")
    void markShipping_alreadyShipping_throwsIllegalStateException() {
        Order order = createOrderWithStatus("shipping");

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    @DisplayName("markShipping: delivered 상태 → IllegalStateException (방어적)")
    void markShipping_fromDelivered_throwsIllegalStateException() {
        Order order = createOrderWithStatus("delivered");

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markShipping: cancelled 상태 → IllegalStateException (방어적)")
    void markShipping_fromCancelled_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markCancelled();

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markShipping: refunded 상태 → IllegalStateException (방어적)")
    void markShipping_fromRefunded_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markShipping: 오류 메시지에 현재 상태 포함")
    void markShipping_errorMessageContainsCurrentStatus() {
        Order order = createPendingOrder(); // status = "pending"

        assertThatThrownBy(() -> order.markShipping())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    // ============================================================
    // 회귀 보장 — 019/018/016 전이 영향 없음
    // ============================================================

    @Test
    @DisplayName("markShipping 호출 전후 markPaid 영향 없음 — 016 회귀 없음")
    void markPaid_regression_notAffectedByMarkShipping() {
        Order order = createPendingOrder();
        order.markPaid();

        assertThat(order.getStatus()).isEqualTo("paid");
    }

    @Test
    @DisplayName("markShipping 호출 전후 markPreparing 영향 없음 — 019 회귀 없음")
    void markPreparing_regression_notAffectedByMarkShipping() {
        Order order = createOrderWithStatus("paid");
        order.markPreparing();

        assertThat(order.getStatus()).isEqualTo("preparing");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPendingOrder() {
        return Order.create(1L, "ORD-020-001", BigDecimal.valueOf(10000),
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
