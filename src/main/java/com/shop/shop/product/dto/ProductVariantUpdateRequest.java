package com.shop.shop.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 variant 수정 요청 DTO (REST).
 *
 * <p>생성 요청과 동일한 필드 구조.
 * 수정 시 SKU 중복 검사에서 자기 자신을 제외(V3 불변식).
 */
public record ProductVariantUpdateRequest(
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
