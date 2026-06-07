package com.shop.shop.web.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 장바구니 담기 폼 백킹 객체 (상세화면 → POST /cart/items).
 *
 * <p>Entity 직접 바인딩 금지. CartItemAddRequest(REST DTO)와 별도.
 */
@Getter
@Setter
@NoArgsConstructor
public class CartItemAddForm {

    /**
     * 담을 variant ID (필수).
     */
    @NotNull(message = "상품 옵션을 선택해 주세요.")
    private Long variantId;

    /**
     * 수량 (1 이상).
     */
    @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
    private int quantity = 1;
}
