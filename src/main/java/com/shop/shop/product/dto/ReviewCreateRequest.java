package com.shop.shop.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 리뷰 작성 요청 DTO.
 *
 * <p>product_id는 요청 바디로 받지 않는다 — order_item에서 서버가 도출(위조 차단).
 */
public record ReviewCreateRequest(

        @NotNull(message = "orderItemId는 필수입니다.")
        Long orderItemId,

        @NotNull(message = "rating은 필수입니다.")
        @Min(value = 1, message = "rating은 최소 1이어야 합니다.")
        @Max(value = 5, message = "rating은 최대 5이어야 합니다.")
        Integer rating,

        @Size(max = 1000, message = "content는 최대 1000자입니다.")
        String content
) {
}
