package com.shop.shop.member.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 회원 정보 수정 REST 요청 DTO (record).
 *
 * <p>name: 필수(@NotBlank). phone: optional (007 규칙 계승 — null/빈 문자열 허용).
 * email/role/password는 이 경로로 변경 불가 (Constraint).
 */
public record ProfileUpdateRequest(

        @NotBlank(message = "이름은 필수입니다.")
        String name,

        String phone   // optional
) {
}
