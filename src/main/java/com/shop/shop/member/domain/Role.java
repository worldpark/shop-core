package com.shop.shop.member.domain;

/**
 * 회원 권한 열거형.
 * DB 저장값은 enum 상수명(대문자) — VARCHAR CHECK (ADMIN/SELLER/CONSUMER).
 * Spring Security 권한명: ROLE_ prefix 부여 (authority() 메서드 사용).
 *
 * 계층: ROLE_ADMIN > ROLE_SELLER > ROLE_CONSUMER (RoleHierarchy 빈 정의).
 */
public enum Role {

    CONSUMER,
    SELLER,
    ADMIN;

    /** Spring Security GrantedAuthority 문자열 변환 — "ROLE_" + 상수명 */
    public String authority() {
        return "ROLE_" + name();
    }
}
