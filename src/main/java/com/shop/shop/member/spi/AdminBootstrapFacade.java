package com.shop.shop.member.spi;

import com.shop.shop.common.exception.AdminAlreadyExistsException;
import com.shop.shop.common.exception.DuplicateEmailException;

/**
 * 최초 ADMIN 부트스트랩 View 전용 facade (published port).
 *
 * <p>web 모듈의 {@code AdminSetupViewController}와 {@code LoginViewController}가
 * member 도메인 내부 Service를 직접 참조하지 않도록 이 facade를 경유한다.
 * 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 * 서블릿 타입(HttpServletRequest 등)을 파라미터·반환 타입으로 노출하지 않는다.
 */
public interface AdminBootstrapFacade {

    /**
     * ADMIN 계정 존재 여부 확인.
     *
     * <p>GET /login 게이트와 GET /setup/admin 게이트에서 호출된다.
     * ADMIN이 0명이면 부트스트랩 화면으로 유도하고, 1명 이상이면 일반 로그인 화면을 반환한다.
     *
     * @return ADMIN이 1명 이상이면 true, 0명이면 false
     */
    boolean adminExists();

    /**
     * 최초 ADMIN 계정 생성.
     *
     * <p>BCrypt 해시, 이메일 정규화, ADMIN 중복 가드, 이메일 중복 검사를 포함한 도메인 로직을 수행한다.
     * {@code MemberRegisteredEvent} 미발행 (부트스트랩 = 시스템 행위).
     *
     * @param email       ADMIN 이메일
     * @param rawPassword ADMIN 비밀번호 원문
     * @param name        ADMIN 이름
     * @throws AdminAlreadyExistsException ADMIN이 이미 존재함 (409)
     * @throws DuplicateEmailException     이메일 중복 또는 동시성 경합 unique 위반
     */
    void createFirstAdmin(String email, String rawPassword, String name);
}
