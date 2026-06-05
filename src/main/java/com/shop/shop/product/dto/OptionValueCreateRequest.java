package com.shop.shop.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 옵션값 생성 요청 DTO (REST).
 */
public record OptionValueCreateRequest(
        @NotBlank(message = "옵션값은 필수입니다.")
        String value
) {
}
