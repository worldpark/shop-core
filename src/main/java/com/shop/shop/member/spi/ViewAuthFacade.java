package com.shop.shop.member.spi;

import com.shop.shop.security.AuthTokenIssuer;

/**
 * View(브라우저) 인증 전용 facade (published port).
 *
 * <p>web 모듈의 {@code CookieLoginViewController}가 member 도메인 내부 Service를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>facade 시그니처는 web/서블릿 타입(HttpServletRequest/Response, Cookie 등)을 받지 않는다.
 * scalar(String)/값 객체만 노출하고, 쿠키 I/O(Set-Cookie, readAccess)는 web 컨트롤러 책임이다
 * (architecture-rule: published API가 web을 역참조하지 않게 한다).
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 *
 * <p>설계 노트: plan §2는 ViewAuthService(member/service) 직접 주입을 명시했으나
 * WebModuleStructureTest·ModularityTests(web→member.service 금지, facade는 서블릿 타입 비노출) 위반으로
 * spi facade 경유 + 쿠키 I/O를 web으로 이관하는 방향으로 구현했다.
 */
public interface ViewAuthFacade {

    /**
     * 폼 자격증명 인증 후 JWT 토큰을 발급한다.
     *
     * <p>처리 순서: authenticate → AuthTokenIssuer.issue → recordLoginByEmail.
     * 쿠키 설정(Set-Cookie)은 호출자(컨트롤러)가 {@code AuthCookies.writeTokens}로 수행한다.
     *
     * @param email       폼 username 파라미터 (이메일)
     * @param rawPassword 폼 password 파라미터
     * @return 발급된 access/refresh 토큰 값 객체
     * @throws com.shop.shop.common.exception.InvalidCredentialsException 자격증명 불일치
     */
    AuthTokenIssuer.IssuedTokens login(String email, String rawPassword);

    /**
     * access 토큰을 받아 refresh revoke + access blacklist 를 수행한다 (로그아웃 store 작업).
     *
     * <p>쿠키 읽기(readAccess)·쿠키 제거(clearTokens)는 호출자(컨트롤러)가 직접 수행한다.
     * access 토큰이 없거나 만료·위조된 경우 store 작업을 건너뛰고 조용히 반환한다(fail-safe).
     *
     * @param accessToken access 쿠키에서 읽은 JWT 문자열 (null 허용 — fail-safe)
     */
    void revoke(String accessToken);
}
