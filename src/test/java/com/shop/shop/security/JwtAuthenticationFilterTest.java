package com.shop.shop.security;

import com.shop.shop.security.support.FakeRefreshTokenStore;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * JwtAuthenticationFilter 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>API 체인 (헤더 소스 + userId principal): principal instanceof Long 무회귀</li>
 *   <li>View 체인 (쿠키 소스 + email principal): getName()=email 확인</li>
 *   <li>blacklist 토큰 → 미인증</li>
 *   <li>만료/위조 토큰 → 미인증</li>
 *   <li>토큰 없음 → 미인증</li>
 * </ul>
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-for-jwt-filter-unit-test-32chars!!";
    private static final String EMAIL = "user@example.com";
    private static final long USER_ID = 42L;
    private static final List<String> ROLES = List.of("ROLE_CONSUMER");

    private JwtTokenProvider jwtTokenProvider;
    private FakeRefreshTokenStore fakeRefreshTokenStore;
    private JwtAuthenticationFilter apiFilter;
    private JwtAuthenticationFilter viewFilter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test");
        jwtTokenProvider = new JwtTokenProvider(props);
        fakeRefreshTokenStore = new FakeRefreshTokenStore();

        apiFilter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                fakeRefreshTokenStore,
                JwtAuthenticationFilter.bearerHeaderExtractor(),
                JwtAuthenticationFilter.userIdPrincipalFactory(jwtTokenProvider)
        );

        viewFilter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                fakeRefreshTokenStore,
                JwtAuthenticationFilter.accessTokenCookieExtractor(),
                JwtAuthenticationFilter.emailPrincipalFactory(jwtTokenProvider)
        );

        SecurityContextHolder.clearContext();
    }

    // =========================================================
    // API 필터 (헤더 소스 + userId principal)
    // =========================================================

    @Test
    @DisplayName("API 필터 — Bearer 헤더 유효 → principal instanceof Long (무회귀: (long) getPrincipal() 캐스팅 보존)")
    void api_filter_valid_bearer_token_principal_is_long() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        // REST 사용처 (CartServiceResponse:35, MemberServiceResponse:55) 무회귀
        assertThat(auth.getPrincipal()).isInstanceOf(Long.class);
        assertThat((long) auth.getPrincipal()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("API 필터 — Bearer 헤더 유효 → getName()이 userId 문자열 (Long.toString)")
    void api_filter_valid_bearer_token_getName_is_userId_string() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("API 필터 — Authorization 헤더 없음 → 미인증")
    void api_filter_no_bearer_header_is_unauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("API 필터 — access_token 쿠키가 있어도 헤더 소스이므로 미인증")
    void api_filter_ignores_cookie_source() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // =========================================================
    // View 필터 (쿠키 소스 + email principal)
    // =========================================================

    @Test
    @DisplayName("View 필터 — access_token 쿠키 유효 → getName()=email (CurrentActorResolver 무회귀)")
    void view_filter_valid_cookie_token_getName_is_email() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("View 필터 — access_token 쿠키 유효 → principal이 email(String)")
    void view_filter_valid_cookie_token_principal_is_email_string() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("View 필터 — 쿠키 없음 → 미인증")
    void view_filter_no_cookie_is_unauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("View 필터 — Authorization Bearer 헤더가 있어도 쿠키 소스이므로 미인증")
    void view_filter_ignores_bearer_header() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // =========================================================
    // 공통: blacklist, 위조, 만료
    // =========================================================

    @Test
    @DisplayName("API 필터 — blacklist 등록 토큰 → 미인증")
    void api_filter_blacklisted_token_is_unauthenticated() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        Claims claims = jwtTokenProvider.parse(accessToken);
        String jti = jwtTokenProvider.extractJti(claims);
        fakeRefreshTokenStore.blacklistAccess(jti, Duration.ofMinutes(10));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("View 필터 — blacklist 등록 쿠키 토큰 → 미인증")
    void view_filter_blacklisted_cookie_token_is_unauthenticated() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);
        Claims claims = jwtTokenProvider.parse(accessToken);
        String jti = jwtTokenProvider.extractJti(claims);
        fakeRefreshTokenStore.blacklistAccess(jti, Duration.ofMinutes(10));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("API 필터 — 위조된 토큰 → 미인증")
    void api_filter_tampered_token_is_unauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.tampered.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        apiFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("View 필터 — roles 클레임 포함 authorities 확인")
    void view_filter_authorities_from_roles_claim() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(USER_ID, EMAIL, List.of("ROLE_ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, accessToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        viewFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
