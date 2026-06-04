package com.shop.shop.product.dto;

import com.shop.shop.product.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 상품 수정 요청 DTO (REST SELLER).
 *
 * <p>status: 수정 시 명시적으로 전달. enum 미정의 값 → 역직렬화 400.
 * categoryId: nullable — null이면 미분류.
 * basePrice: BigDecimal, ≥ 0.
 */
public record ProductUpdateRequest(
        Long categoryId,

        @NotBlank(message = "상품명은 필수입니다.")
        String name,

        String description,

        @NotNull(message = "기본 가격은 필수입니다.")
        @DecimalMin(value = "0.0", message = "기본 가격은 0 이상이어야 합니다.")
        BigDecimal basePrice,

        @NotNull(message = "상태는 필수입니다.")
        ProductStatus status
) {
}
