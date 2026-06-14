package com.shop.shop.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 재설정 요청 REST DTO.
 *
 * <p>이메일만 수신한다. 이메일 존재 여부와 무관하게 응답은 동일(enumeration 방지).
 */
public record PasswordResetRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}
