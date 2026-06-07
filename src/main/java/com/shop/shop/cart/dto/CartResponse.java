package com.shop.shop.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 장바구니 응답 DTO.
 *
 * <p>totalAmount는 구매가능(available && stockEnough) 항목만 합산한다(주문 가능 금액 기준).
 * hasUnavailableItem: items 중 available=false 또는 stockEnough=false가 1개 이상이면 true.
 */
public record CartResponse(
        long cartId,
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal totalAmount,
        boolean hasUnavailableItem
) {
}
