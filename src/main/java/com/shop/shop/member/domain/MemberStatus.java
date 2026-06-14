package com.shop.shop.member.domain;

/**
 * 회원 계정 상태 enum.
 *
 * <p>DB CHECK 제약: status IN ('ACTIVE', 'WITHDRAWN') — V6 마이그레이션 정의.
 * {@code @Enumerated(EnumType.STRING)}로 상수명과 1:1 매핑.
 *
 * <p>ACTIVE : 정상 활성 계정 (기본값 — 가입 시 자동 설정).
 * WITHDRAWN: 탈퇴 처리된 계정 (소프트 삭제 — 물리 삭제 금지).
 */
public enum MemberStatus {

    /** 정상 활성 계정. */
    ACTIVE,

    /** 탈퇴 처리된 계정 (소프트 삭제). */
    WITHDRAWN
}
