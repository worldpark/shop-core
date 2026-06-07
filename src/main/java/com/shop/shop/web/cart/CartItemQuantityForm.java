package com.shop.shop.web.cart;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 장바구니 수량 변경 폼 백킹 객체 (장바구니 화면 → POST /cart/items/{cartItemId}).
 *
 * <p>절대값 수량 변경. last-write-wins 정책.
 */
@Getter
@Setter
@NoArgsConstructor
public class CartItemQuantityForm {

    /**
     * 변경 후 수량 절대값 (1 이상).
     */
    @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
    private int quantity = 1;
}
