package com.shop.shop.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * JWT 쿠키 read/write 유틸리티 — 쿠키 이름·속성·TTL 단일 정의(SSOT).
 *
 * <p>쿠키 표준:
 * <ul>
 *   <li>이름: {@code access_token} / {@code refresh_token}</li>
 *   <li>속성: HttpOnly · Secure · SameSite=Lax · Path=/</li>
 *   <li>maxAge: {@link JwtProperties#accessTtl()} / {@link JwtProperties#refreshTtl()} (SSOT)</li>
 * </ul>
 *
 * <p>SameSite 작성: {@link ResponseCookie} 빌더로 직렬화 후
 * {@code response.addHeader("Set-Cookie", ...)} — jakarta.servlet.http.Cookie는 SameSite 미지원.
 * (053 선례와 동일 방식)
 *
 * <p>발급(writeTokens/writeAccess)·무음 refresh(writeAccess)·로그아웃(clearTokens)이 공용으로 이 클래스를 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCookies {

    /** JWT access token 쿠키 이름 */
    public static final String ACCESS_TOKEN = "access_token";

    /** JWT refresh token 쿠키 이름 */
    public static final String REFRESH_TOKEN = "refresh_token";

    private static final String SAME_SITE = "Lax";
    private static final String PATH = "/";

    private final JwtProperties jwtProperties;

    /**
     * access + refresh 쿠키를 응답에 설정한다 (로그인 성공 시 호출).
     *
     * @param response     HTTP 응답
     * @param issuedTokens 발급된 토큰 값 객체
     */
    public void writeTokens(HttpServletResponse response, AuthTokenIssuer.IssuedTokens issuedTokens) {
        writeAccess(response, issuedTokens.accessToken());
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN, issuedTokens.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(jwtProperties.refreshTtl())
                .build();
        response.addHeader("Set-Cookie", refreshCookie.toString());
        log.debug("토큰 쿠키 설정 완료 (access + refresh)");
    }

    /**
     * access 쿠키만 갱신한다 (무음 refresh 시 호출).
     *
     * @param response    HTTP 응답
     * @param accessToken 새로 발급된 JWT access token 문자열
     */
    public void writeAccess(HttpServletResponse response, String accessToken) {
        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(jwtProperties.accessTtl())
                .build();
        response.addHeader("Set-Cookie", accessCookie.toString());
        log.debug("access 쿠키 설정 완료");
    }

    /**
     * access + refresh 쿠키를 만료시킨다 (maxAge=0, 로그아웃 시 호출).
     *
     * @param response HTTP 응답
     */
    public void clearTokens(HttpServletResponse response) {
        ResponseCookie clearAccess = ResponseCookie.from(ACCESS_TOKEN, "")
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(0)
                .build();
        ResponseCookie clearRefresh = ResponseCookie.from(REFRESH_TOKEN, "")
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", clearAccess.toString());
        response.addHeader("Set-Cookie", clearRefresh.toString());
        log.debug("토큰 쿠키 제거 완료");
    }

    /**
     * 요청에서 {@code access_token} 쿠키 값을 읽는다.
     *
     * @param request HTTP 요청
     * @return access token 문자열 또는 null
     */
    public String readAccess(HttpServletRequest request) {
        return readCookie(request, ACCESS_TOKEN);
    }

    /**
     * 요청에서 {@code refresh_token} 쿠키 값을 읽는다.
     *
     * @param request HTTP 요청
     * @return refresh token 문자열 또는 null
     */
    public String readRefresh(HttpServletRequest request) {
        return readCookie(request, REFRESH_TOKEN);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
