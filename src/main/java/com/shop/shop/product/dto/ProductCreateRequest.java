package com.shop.shop.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 상품 등록 요청 DTO (REST SELLER).
 *
 * <p>status는 미수신 — 등록 시 항상 DRAFT 강제(ProductService가 처리).
 * categoryId: nullable — null이면 미분류 상품.
 * basePrice: BigDecimal, ≥ 0 (부동소수점 금지).
 */
public record ProductCreateRequest(
        Long categoryId,

        @NotBlank(message = "상품명은 필수입니다.")
        String name,

        String description,

        @NotNull(message = "기본 가격은 필수입니다.")
        @DecimalMin(value = "0.0", message = "기본 가격은 0 이상이어야 합니다.")
        BigDecimal basePrice
) {
}
