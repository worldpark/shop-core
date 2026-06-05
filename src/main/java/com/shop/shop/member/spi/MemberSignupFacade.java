package com.shop.shop.member.spi;

import com.shop.shop.common.exception.DuplicateEmailException;

/**
 * 회원가입 View 전용 facade (published port).
 *
 * <p>web 모듈의 회원가입 ViewController가 member 도메인 내부 Service를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface MemberSignupFacade {

    /**
     * 회원 가입 처리.
     *
     * <p>비밀번호 BCrypt 해시, 이메일 정규화, 이메일 중복 검사를 포함한 도메인 로직을 수행한다.
     * 실패 시 {@link DuplicateEmailException}(common — OPEN 모듈)을 전파한다.
     * 반환값 없음(View는 미사용 — 성공 시 redirect 처리).
     *
     * @param email    가입 이메일
     * @param password 비밀번호 원문
     * @param name     이름
     * @param phone    전화번호 (null/빈 문자열 허용)
     * @throws DuplicateEmailException 이메일 중복 또는 동시성 경합 unique 위반
     */
    void signup(String email, String password, String name, String phone);
}
