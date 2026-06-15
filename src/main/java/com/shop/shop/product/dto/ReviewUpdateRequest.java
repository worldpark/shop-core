package com.shop.shop.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 리뷰 수정 요청 DTO.
 *
 * <p>rating과 content만 수정 가능. productId/userId/orderItemId는 불변.
 */
public record ReviewUpdateRequest(

        @NotNull(message = "rating은 필수입니다.")
        @Min(value = 1, message = "rating은 최소 1이어야 합니다.")
        @Max(value = 5, message = "rating은 최대 5이어야 합니다.")
        Integer rating,

        @Size(max = 1000, message = "content는 최대 1000자입니다.")
        String content
) {
}
