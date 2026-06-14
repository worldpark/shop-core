package com.shop.shop.member.dto;

/**
 * 계정 설정 화면 표시용 DTO (scalar 값만).
 *
 * <p>AccountFacade.getAccountInfo 반환 타입.
 * 비밀번호 해시·role·토큰·Redis 상태 등 민감 정보 비포함 (Constraint).
 * web이 member Entity를 직접 알 필요 없도록 scalar만 노출.
 */
public record AccountInfo(
        String email,
        String name,
        String phone   // nullable (optional 필드)
) {
}
