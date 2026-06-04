package com.shop.shop.member.dto;

import com.shop.shop.member.domain.User;

/**
 * REST 회원가입 응답 DTO.
 *
 * <p>password/passwordHash는 포함하지 않는다 (Constraint — 비밀번호 원문/해시 미노출).
 * Entity(User)를 직접 반환하지 않고 이 DTO로 변환한다.
 */
public record SignupResponse(
        long memberId,
        String email,
        String name,
        String role
) {

    public static SignupResponse from(User user) {
        return new SignupResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name()
        );
    }
}
