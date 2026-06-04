package com.shop.shop.member;

/**
 * 회원 비밀번호 정책 상수.
 *
 * <p>정책 세부값을 상수로 추적한다. 환경별 가변 요구가 없어 @ConfigurationProperties는 과설계(YAGNI).
 * {@code @Size(min = MemberPasswordPolicy.MIN_LENGTH)} 참조 — 컴파일 상수 요구 충족.
 *
 * <p>정책 변경 시 이 클래스만 수정하면 SignupRequest·SignupForm 검증 어노테이션이 동시 적용된다.
 */
public final class MemberPasswordPolicy {

    /** 비밀번호 최소 길이 (8자). 정책 출처: 007-backend-shop-core-member-signup-with-view 요구사항 */
    public static final int MIN_LENGTH = 8;

    private MemberPasswordPolicy() {
        // 인스턴스화 금지 — 상수 클래스
    }
}
