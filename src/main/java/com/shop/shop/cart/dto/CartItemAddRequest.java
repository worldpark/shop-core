package com.shop.shop.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 장바구니 담기 REST 요청 DTO.
 *
 * <p>폼 바인딩은 web/cart CartItemAddForm 별도(view-implementor 담당).
 */
public record CartItemAddRequest(
        @NotNull(message = "variantId는 필수입니다.") Long variantId,
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.") int quantity
) {
}
