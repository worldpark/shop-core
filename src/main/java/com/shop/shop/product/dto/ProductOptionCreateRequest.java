package com.shop.shop.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 상품 옵션 생성 요청 DTO (REST).
 */
public record ProductOptionCreateRequest(
        @NotBlank(message = "옵션명은 필수입니다.")
        String name
) {
}
