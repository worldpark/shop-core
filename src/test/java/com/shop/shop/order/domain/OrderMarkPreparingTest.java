package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.markPreparing() 단위 테스트 (019 신규 + 016/018 회귀 보장).
 *
 * <p>검증:
 * <ul>
 *   <li>markPreparing: paid → preparing 전이 성공 (첫 배송 생성 rollup)</li>
 *   <li>markPreparing: preparing 재호출 → 멱등 no-op (추가 배송 생성 시 status 불변)</li>
 *   <li>markPreparing: 그 외(pending/shipping/delivered/cancelled/refunded) → IllegalStateException</li>
 *   <li>016/018 회귀: markPaid/markCancelled/markRefunded 그린 유지</li>
 * </ul>
 */
class OrderMarkPreparingTest {

    // ============================================================
    // markPreparing 성공 / 멱등
    // ============================================================

    @Test
    @DisplayName("markPreparing: paid → preparing 전이 성공 (첫 배송 생성 rollup)")
    void markPreparing_fromPaid_success() {
        Order order = createPendingOrder();
        order.markPaid(); // pending → paid
        assertThat(order.getStatus()).isEqualTo("paid");

        order.markPreparing();

        assertThat(order.getStatus()).isEqualTo("preparing");
    }

    @Test
    @DisplayName("markPreparing: preparing 재호출 → 멱등 no-op (추가 배송 생성 시 status 불변)")
    void markPreparing_alreadyPreparing_idempotent() {
        Order order = createOrderWithStatus("preparing");

        order.markPreparing(); // 멱등 재호출

        assertThat(order.getStatus()).isEqualTo("preparing"); // 불변
    }

    // ============================================================
    // markPreparing 금지 (부작용 전 상위에서 차단 — 방어적)
    // ============================================================

    @Test
    @DisplayName("markPreparing: pending → IllegalStateException")
    void markPreparing_fromPending_throwsIllegalStateException() {
        Order order = createPendingOrder();

        assertThatThrownBy(() -> order.markPreparing())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
    }

    @Test
    @DisplayName("markPreparing: shipping → IllegalStateException")
    void markPreparing_fromShipping_throwsIllegalStateException() {
        Order order = createOrderWithStatus("shipping");

        assertThatThrownBy(() -> order.markPreparing())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPreparing: delivered → IllegalStateException")
    void markPreparing_fromDelivered_throwsIllegalStateException() {
        Order order = createOrderWithStatus("delivered");

        assertThatThrownBy(() -> order.markPreparing())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPreparing: cancelled → IllegalStateException")
    void markPreparing_fromCancelled_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markCancelled();

        assertThatThrownBy(() -> order.markPreparing())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markPreparing: refunded → IllegalStateException")
    void markPreparing_fromRefunded_throwsIllegalStateException() {
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();

        assertThatThrownBy(() -> order.markPreparing())
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // 016/018 회귀 보장
    // ============================================================

    @Test
    @DisplayName("markPaid: pending → paid 전이 — 016 회귀 없음")
    void markPaid_regression() {
        Order order = createPendingOrder();
        order.markPaid();
        assertThat(order.getStatus()).isEqualTo("paid");
    }

    @Test
    @DisplayName("markCancelled: pending → cancelled 전이 — 018 회귀 없음")
    void markCancelled_regression() {
        Order order = createPendingOrder();
        order.markCancelled();
        assertThat(order.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("markRefunded: paid → refunded 전이 — 018 회귀 없음")
    void markRefunded_regression() {
        Order order = createPendingOrder();
        order.markPaid();
        order.markRefunded();
        assertThat(order.getStatus()).isEqualTo("refunded");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Order createPendingOrder() {
        return Order.create(1L, "ORD-019-001", BigDecimal.valueOf(10000),
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
