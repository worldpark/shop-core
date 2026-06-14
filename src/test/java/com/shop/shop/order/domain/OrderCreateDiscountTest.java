package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order.create 오버로드 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>9-arg 오버로드: finalAmount = itemsAmount - discount</li>
 *   <li>8-arg 오버로드: discount=ZERO 위임 (기존 테스트 그린)</li>
 *   <li>discount > itemsAmount → IllegalStateException</li>
 *   <li>discount < 0 → IllegalStateException</li>
 * </ul>
 */
class OrderCreateDiscountTest {

    private static final long USER_ID = 1L;
    private static final String ORDER_NUMBER = "ORD-TEST-001";

    @Test
    @DisplayName("9-arg: finalAmount = itemsAmount - discountAmount")
    void create9arg_finalAmount_isItemsMinusDiscount() {
        BigDecimal items = new BigDecimal("10000");
        BigDecimal discount = new BigDecimal("2000");

        Order order = Order.create(USER_ID, ORDER_NUMBER, items, discount,
                "수령인", "010", "12345", "서울", null);

        assertThat(order.getItemsAmount()).isEqualByComparingTo("10000");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("2000");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("8000");
        assertThat(order.getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("8-arg 오버로드: discount=ZERO, finalAmount=itemsAmount (기존 흐름)")
    void create8arg_discountZero_finalEqualsItems() {
        BigDecimal items = new BigDecimal("10000");

        Order order = Order.create(USER_ID, ORDER_NUMBER, items,
                "수령인", "010", "12345", "서울", null);

        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("9-arg: discount=ZERO → finalAmount=itemsAmount")
    void create9arg_discountZero_finalEqualsItems() {
        BigDecimal items = new BigDecimal("5000");

        Order order = Order.create(USER_ID, ORDER_NUMBER, items, BigDecimal.ZERO,
                "수령인", "010", "12345", "서울", null);

        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("9-arg: discount > itemsAmount → IllegalStateException (음수 final 방지)")
    void create9arg_discountExceedsItems_throwsIllegalState() {
        BigDecimal items = new BigDecimal("5000");
        BigDecimal discount = new BigDecimal("6000"); // > items

        assertThatThrownBy(() -> Order.create(USER_ID, ORDER_NUMBER, items, discount,
                "수령인", "010", "12345", "서울", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalAmount");
    }

    @Test
    @DisplayName("9-arg: discount < 0 → IllegalStateException")
    void create9arg_negativeDiscount_throwsIllegalState() {
        BigDecimal items = new BigDecimal("5000");
        BigDecimal discount = new BigDecimal("-100");

        assertThatThrownBy(() -> Order.create(USER_ID, ORDER_NUMBER, items, discount,
                "수령인", "010", "12345", "서울", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discountAmount");
    }

    @Test
    @DisplayName("9-arg: discount == itemsAmount → finalAmount = 0 (허용)")
    void create9arg_discountEqualsItems_finalAmountZero() {
        BigDecimal items = new BigDecimal("5000");

        Order order = Order.create(USER_ID, ORDER_NUMBER, items, items,
                "수령인", "010", "12345", "서울", null);

        assertThat(order.getFinalAmount()).isEqualByComparingTo("0");
    }
}
