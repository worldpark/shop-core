package com.shop.shop.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 variant 생성 요청 DTO (REST).
 *
 * <p>price: BigDecimal, ≥ 0 (부동소수점 금지).
 * stock: ≥ 0.
 * optionValueIds: 선택할 옵션값 ID 목록 (null이면 빈 목록으로 처리).
 */
public record ProductVariantCreateRequest(
        @NotBlank(message = "SKU는 필수입니다.")
        String sku,

        @NotNull(message = "가격은 필수입니다.")
        @DecimalMin(value = "0.0", message = "가격은 0 이상이어야 합니다.")
        BigDecimal price,

        @NotNull(message = "재고는 필수입니다.")
        @Min(value = 0, message = "재고는 0 이상이어야 합니다.")
        Integer stock,

        boolean active,

        List<Long> optionValueIds
) {
}
