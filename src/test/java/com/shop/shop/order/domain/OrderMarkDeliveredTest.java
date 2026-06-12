package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.markDelivered() 단위 테스트 (Task 021).
 *
 * <p>검증:
 * <ul>
 *   <li>markDelivered: shipping → delivered 전이 성공</li>
 *   <li>markDelivered: 그 외 상태(paid/preparing/delivered/cancelled/refunded) → IllegalStateException</li>
 *   <li>멱등 없음(정합4) — 서비스가 rollup 조건 충족 && 주문이 shipping일 때만 호출</li>
 * </ul>
 */
class OrderMarkDeliveredTest {

    // ============================================================
    // 성공 — shipping → delivered
    // ============================================================

    @Test
    @DisplayName("markDelivered: shipping → delivered 전이 성공")
    void markDelivered_fromShipping_success() {
        Order order = createOrderWithStatus("shipping");

        order.markDelivered();

        assertThat(order.getStatus()).isEqualTo("delivered");
    }

    // ============================================================
    // 실패 — shipping 이외 상태
    // ============================================================

    @Test
    @DisplayName("markDelivered: paid 상태 → IllegalStateException")
    void markDelivered_fromPaid_throwsIllegalStateException() {
        Order order = createOrderWithStatus("paid");

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    @DisplayName("markDelivered: preparing 상태 → IllegalStateException")
    void markDelivered_fromPreparing_throwsIllegalStateException() {
        Order order = createOrderWithStatus("preparing");

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markDelivered: 이미 delivered → IllegalStateException (멱등 없음, 서비스가 rollup 조건 검증, 정합4)")
    void markDelivered_alreadyDelivered_throwsIllegalStateException() {
        Order order = createOrderWithStatus("delivered");

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivered");
    }

    @Test
    @DisplayName("markDelivered: cancelled 상태 → IllegalStateException (방어적)")
    void markDelivered_fromCancelled_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markCancelled();

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markDelivered: refunded 상태 → IllegalStateException (방어적)")
    void markDelivered_fromRefunded_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markDelivered: pending 상태 → IllegalStateException (방어적)")
    void markDelivered_fromPending_throwsIllegalStateException() {
        Order order = createPendingOrder();

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    @DisplayName("markDelivered: 오류 메시지에 현재 상태 포함")
    void markDelivered_errorMessageContainsCurrentStatus() {
        Order order = createOrderWithStatus("preparing");

        assertThatThrownBy(() -> order.markDelivered())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preparing");
    }

    // ============================================================
    // 회귀 보장 — 020/019/018/016 전이 영향 없음
    // ============================================================

    @Test
    @DisplayName("markDelivered 추가 후 markShipping 동작 영향 없음 — 020 회귀 없음")
    void markShipping_regression_notAffectedByMarkDelivered() {
        Order order = createOrderWithStatus("preparing");
        order.markShipping();

        assertThat(order.getStatus()).isEqualTo("shipping");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPendingOrder() {
        return Order.create(1L, "ORD-021-001", BigDecimal.valueOf(10000),
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
