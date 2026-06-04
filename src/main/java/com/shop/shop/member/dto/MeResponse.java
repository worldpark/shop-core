package com.shop.shop.member.dto;

import com.shop.shop.member.domain.User;

/**
 * 내 정보 조회 응답 DTO.
 * Entity(User)를 직접 반환하지 않고 이 DTO로 변환해 반환한다.
 */
public record MeResponse(
        long id,
        String email,
        String name,
        String role
) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name()
        );
    }
}
