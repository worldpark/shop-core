package com.shop.shop.member.dto;

import com.shop.shop.member.domain.User;

import java.time.Instant;

/**
 * 관리자 회원 목록 항목 DTO.
 *
 * <p>민감 정보(password_hash, refresh token, Redis 상태 등)를 절대 포함하지 않는다 (Constraint).
 * Entity를 API 응답/View 모델에 직접 전달하지 않고 이 DTO로 변환한다.
 *
 * @param memberId  회원 식별자
 * @param email     이메일
 * @param name      이름
 * @param role      권한 (문자열: ADMIN/SELLER/CONSUMER)
 * @param createdAt 가입일시 (DB 소유 Instant)
 */
public record MemberSummaryResponse(
        long memberId,
        String email,
        String name,
        String role,
        Instant createdAt
) {

    /**
     * {@code User} 엔티티로부터 DTO 생성.
     * role은 {@code Role.name()}(대문자 문자열)으로 변환.
     *
     * @param user 회원 엔티티
     * @return MemberSummaryResponse
     */
    public static MemberSummaryResponse from(User user) {
        return new MemberSummaryResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }
}
