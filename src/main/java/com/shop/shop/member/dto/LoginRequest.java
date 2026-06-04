package com.shop.shop.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO.
 * 비밀번호는 로그에 남기지 않는다 (toString 포함 금지).
 */
public record LoginRequest(
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @NotBlank(message = "이메일을 입력해주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        String password
) {
    @Override
    public String toString() {
        // 비밀번호 원문 로그 노출 방지
        return "LoginRequest{email='" + email + "', password='[PROTECTED]'}";
    }
}
