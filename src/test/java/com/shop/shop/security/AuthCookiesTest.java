package com.shop.shop.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthCookies 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>writeTokens: access + refresh 쿠키 모두 설정됨</li>
 *   <li>쿠키 속성: HttpOnly·Secure·SameSite=Lax·Path=/ 포함 확인</li>
 *   <li>maxAge: accessTtl / refreshTtl (JwtProperties SSOT)</li>
 *   <li>writeAccess: access 쿠키만 설정됨</li>
 *   <li>clearTokens: maxAge=0으로 두 쿠키 제거</li>
 *   <li>readAccess / readRefresh: 라운드트립 확인</li>
 * </ul>
 */
class AuthCookiesTest {

    private static final String SECRET = "test-secret-key-for-auth-cookies-test-32chars-!!";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private AuthCookies authCookies;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET, ACCESS_TTL, REFRESH_TTL, "test");
        authCookies = new AuthCookies(jwtProperties);
    }

    @Test
    @DisplayName("writeTokens — access_token + refresh_token 두 개 Set-Cookie 헤더 설정")
    void writeTokens_sets_both_cookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthTokenIssuer.IssuedTokens tokens = new AuthTokenIssuer.IssuedTokens(
                "access.token.value", "refresh.token.value", ACCESS_TTL.toSeconds());

        authCookies.writeTokens(response, tokens);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).hasSize(2);

        String accessHeader = findCookieHeader(setCookieHeaders, AuthCookies.ACCESS_TOKEN);
        String refreshHeader = findCookieHeader(setCookieHeaders, AuthCookies.REFRESH_TOKEN);

        assertThat(accessHeader).isNotNull().contains("access.token.value");
        assertThat(refreshHeader).isNotNull().contains("refresh.token.value");
    }

    @Test
    @DisplayName("writeTokens — access_token 쿠키에 HttpOnly·Secure·SameSite=Lax·Path=/ 포함")
    void writeTokens_access_cookie_has_required_attributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthTokenIssuer.IssuedTokens tokens = new AuthTokenIssuer.IssuedTokens(
                "access.token.value", "refresh.token.value", ACCESS_TTL.toSeconds());

        authCookies.writeTokens(response, tokens);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        String accessHeader = findCookieHeader(setCookieHeaders, AuthCookies.ACCESS_TOKEN);

        assertThat(accessHeader).isNotNull();
        assertThat(accessHeader).containsIgnoringCase("HttpOnly");
        assertThat(accessHeader).containsIgnoringCase("Secure");
        assertThat(accessHeader).containsIgnoringCase("SameSite=Lax");
        assertThat(accessHeader).containsIgnoringCase("Path=/");
    }

    @Test
    @DisplayName("writeTokens — refresh_token 쿠키에 HttpOnly·Secure·SameSite=Lax·Path=/ 포함")
    void writeTokens_refresh_cookie_has_required_attributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthTokenIssuer.IssuedTokens tokens = new AuthTokenIssuer.IssuedTokens(
                "access.token.value", "refresh.token.value", ACCESS_TTL.toSeconds());

        authCookies.writeTokens(response, tokens);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        String refreshHeader = findCookieHeader(setCookieHeaders, AuthCookies.REFRESH_TOKEN);

        assertThat(refreshHeader).isNotNull();
        assertThat(refreshHeader).containsIgnoringCase("HttpOnly");
        assertThat(refreshHeader).containsIgnoringCase("Secure");
        assertThat(refreshHeader).containsIgnoringCase("SameSite=Lax");
        assertThat(refreshHeader).containsIgnoringCase("Path=/");
    }

    @Test
    @DisplayName("writeTokens — access maxAge = accessTtl(초) (JwtProperties SSOT)")
    void writeTokens_access_cookie_maxAge_equals_accessTtl() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthTokenIssuer.IssuedTokens tokens = new AuthTokenIssuer.IssuedTokens(
                "access.token.value", "refresh.token.value", ACCESS_TTL.toSeconds());

        authCookies.writeTokens(response, tokens);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        String accessHeader = findCookieHeader(setCookieHeaders, AuthCookies.ACCESS_TOKEN);

        assertThat(accessHeader).isNotNull();
        assertThat(accessHeader).contains("Max-Age=" + ACCESS_TTL.toSeconds());
    }

    @Test
    @DisplayName("writeTokens — refresh maxAge = refreshTtl(초) (JwtProperties SSOT)")
    void writeTokens_refresh_cookie_maxAge_equals_refreshTtl() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthTokenIssuer.IssuedTokens tokens = new AuthTokenIssuer.IssuedTokens(
                "access.token.value", "refresh.token.value", ACCESS_TTL.toSeconds());

        authCookies.writeTokens(response, tokens);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        String refreshHeader = findCookieHeader(setCookieHeaders, AuthCookies.REFRESH_TOKEN);

        assertThat(refreshHeader).isNotNull();
        assertThat(refreshHeader).contains("Max-Age=" + REFRESH_TTL.toSeconds());
    }

    @Test
    @DisplayName("writeAccess — access_token 쿠키만 설정 (refresh 미포함)")
    void writeAccess_sets_only_access_cookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookies.writeAccess(response, "new.access.token");

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).hasSize(1);

        String accessHeader = findCookieHeader(setCookieHeaders, AuthCookies.ACCESS_TOKEN);
        assertThat(accessHeader).isNotNull().contains("new.access.token");

        String refreshHeader = findCookieHeader(setCookieHeaders, AuthCookies.REFRESH_TOKEN);
        assertThat(refreshHeader).isNull();
    }

    @Test
    @DisplayName("clearTokens — access + refresh 두 쿠키 maxAge=0으로 제거")
    void clearTokens_sets_both_cookies_with_maxAge_zero() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authCookies.clearTokens(response);

        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).hasSize(2);

        String accessHeader = findCookieHeader(setCookieHeaders, AuthCookies.ACCESS_TOKEN);
        String refreshHeader = findCookieHeader(setCookieHeaders, AuthCookies.REFRESH_TOKEN);

        assertThat(accessHeader).isNotNull().contains("Max-Age=0");
        assertThat(refreshHeader).isNotNull().contains("Max-Age=0");
    }

    @Test
    @DisplayName("readAccess — 요청에서 access_token 쿠키 값 읽기 (라운드트립)")
    void readAccess_reads_access_token_cookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, "my.access.token"));

        String result = authCookies.readAccess(request);

        assertThat(result).isEqualTo("my.access.token");
    }

    @Test
    @DisplayName("readRefresh — 요청에서 refresh_token 쿠키 값 읽기 (라운드트립)")
    void readRefresh_reads_refresh_token_cookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.REFRESH_TOKEN, "my.refresh.token"));

        String result = authCookies.readRefresh(request);

        assertThat(result).isEqualTo("my.refresh.token");
    }

    @Test
    @DisplayName("readAccess — 쿠키 없을 때 null 반환")
    void readAccess_returns_null_when_no_cookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String result = authCookies.readAccess(request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("readAccess — 다른 쿠키만 있을 때 null 반환")
    void readAccess_returns_null_when_only_other_cookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("some_other_cookie", "some_value"));

        String result = authCookies.readAccess(request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("쿠키 이름 상수: ACCESS_TOKEN=access_token, REFRESH_TOKEN=refresh_token")
    void cookie_name_constants() {
        assertThat(AuthCookies.ACCESS_TOKEN).isEqualTo("access_token");
        assertThat(AuthCookies.REFRESH_TOKEN).isEqualTo("refresh_token");
    }

    private String findCookieHeader(List<String> headers, String cookieName) {
        return headers.stream()
                .filter(h -> h.startsWith(cookieName + "="))
                .findFirst()
                .orElse(null);
    }
}
