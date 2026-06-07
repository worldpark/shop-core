package com.shop.shop.cart.dto;

import jakarta.validation.constraints.Min;

/**
 * 장바구니 수량 변경 REST 요청 DTO.
 *
 * <p>절대값 변경(last-write-wins). 폼 바인딩은 web/cart CartItemQuantityForm 별도(view-implementor 담당).
 */
public record CartItemQuantityUpdateRequest(
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.") int quantity
) {
}
