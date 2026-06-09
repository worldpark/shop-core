package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.markPaid() 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>pending → paid 전이 성공</li>
 *   <li>pending 외 상태에서 전이 시 IllegalStateException</li>
 *   <li>paid 상태에서 재호출 시 도메인 예외</li>
 * </ul>
 */
class OrderMarkPaidTest {

    @Test
    @DisplayName("pending → paid 전이 성공")
    void markPaid_fromPending_success() {
        Order order = Order.create(1L, "ORD-001", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);

        order.markPaid();

        assertThat(order.getStatus()).isEqualTo("paid");
    }

    @Test
    @DisplayName("pending이 아닌 상태(paid)에서 markPaid 호출 시 IllegalStateException")
    void markPaid_fromPaid_throwsIllegalStateException() {
        Order order = Order.create(1L, "ORD-001", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        order.markPaid(); // pending → paid

        assertThatThrownBy(() -> order.markPaid())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    @Test
    @DisplayName("cancelled 상태에서 markPaid 호출 시 IllegalStateException")
    void markPaid_fromCancelled_throwsIllegalStateException() {
        Order order = createOrderWithStatus("cancelled");

        assertThatThrownBy(() -> order.markPaid())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending")
                .hasMessageContaining("cancelled");
    }

    private Order createOrderWithStatus(String status) {
        Order order = Order.create(1L, "ORD-001", BigDecimal.valueOf(10000),
                "수령인", "010-1234-5678", "12345", "서울시", null);
        // status 필드를 리플렉션으로 변경(도메인 테스트 전용)
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
