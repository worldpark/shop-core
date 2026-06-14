package com.shop.shop.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 쿠폰 발급(claim) 요청 DTO.
 */
public record CouponClaimRequest(
        @NotBlank(message = "쿠폰 코드를 입력해 주세요.") String code
) {
}
