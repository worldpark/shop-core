package com.shop.shop.member.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Access Token 재발급 요청 DTO.
 */
public record RefreshRequest(
        @NotBlank(message = "refresh token을 입력해주세요.")
        String refreshToken
) {
}
