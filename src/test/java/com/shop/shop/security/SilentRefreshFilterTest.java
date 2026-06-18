package com.shop.shop.security;

import com.shop.shop.security.support.FakeRefreshTokenStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SilentRefreshFilter 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>access 만료 + refresh 유효 → 새 access 쿠키 발급 + SecurityContext 인증 (email principal)</li>
 *   <li>refresh 만료 → 미인증</li>
 *   <li>access 유효 → 통과 (후속 필터에 위임)</li>
 *   <li>access·refresh 둘 다 없음 → 통과</li>
 *   <li>refresh hash 불일치(탈퇴/재사용) → 미인증</li>
 * </ul>
 */
class SilentRefreshFilterTest {

    private static final String SECRET = "test-secret-key-for-silent-refresh-filter-32chars!!";
    private static final String EMAIL = "view-user@example.com";
    private static final long USER_ID = 99L;
    private static final List<String> ROLES = List.of("ROLE_CONSUMER");

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;
    private FakeRefreshTokenStore fakeRefreshTokenStore;
    private AuthTokenIssuer authTokenIssuer;
    private AuthCookies authCookies;
    private SilentRefreshFilter silentRefreshFilter;

    // 1ms TTL용 short-lived 프로바이더
    private JwtTokenProvider shortLivedProvider;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test");
        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        fakeRefreshTokenStore = new FakeRefreshTokenStore();
        authTokenIssuer = new AuthTokenIssuer(jwtTokenProvider, fakeRefreshTokenStore, jwtProperties);
        authCookies = new AuthCookies(jwtProperties);

        silentRefreshFilter = new SilentRefreshFilter(
                jwtTokenProvider, fakeRefreshTokenStore, authTokenIssuer, authCookies);

        // 즉시 만료되는 access token 발급용
        JwtProperties shortProps = new JwtProperties(SECRET, Duration.ofMillis(1), Duration.ofDays(14), "test");
        shortLivedProvider = new JwtTokenProvider(shortProps);

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("access 만료 + refresh 유효 → 새 access 쿠키 설정 + SecurityContext email principal 인증")
    void access_expired_refresh_valid_issues_new_access_and_authenticates() throws Exception {
        // access 1ms TTL로 즉시 만료
        String expiredAccess = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);
        String refreshToken = jwtTokenProvider.createRefresh(USER_ID);
        fakeRefreshTokenStore.storeRefresh(USER_ID, refreshToken, Duration.ofDays(14));

        // 만료 대기
        Thread.sleep(10);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess),
                new Cookie(AuthCookies.REFRESH_TOKEN, refreshToken)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        // 새 access_token 쿠키 설정 확인
        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).anyMatch(h -> h.startsWith(AuthCookies.ACCESS_TOKEN + "="));

        // SecurityContext에 email principal 인증 설정 확인
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("access 만료 + refresh도 만료(hash 없음) → 미인증, 새 쿠키 미설정")
    void access_expired_refresh_expired_is_unauthenticated() throws Exception {
        // access와 refresh 모두 1ms TTL
        JwtProperties shortProps = new JwtProperties(SECRET, Duration.ofMillis(1), Duration.ofMillis(1), "test");
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortProps);

        String expiredAccess = shortProvider.createAccess(USER_ID, EMAIL, ROLES);
        String expiredRefresh = shortProvider.createRefresh(USER_ID);
        // refresh store에 저장하지 않음 (또는 저장해도 parse에서 만료 예외)
        fakeRefreshTokenStore.storeRefresh(USER_ID, expiredRefresh, Duration.ofMillis(1));

        Thread.sleep(10);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess),
                new Cookie(AuthCookies.REFRESH_TOKEN, expiredRefresh)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        // 새 access 쿠키 미설정
        List<String> setCookieHeaders = response.getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).noneMatch(h -> h.startsWith(AuthCookies.ACCESS_TOKEN + "="));

        // 미인증
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("access 유효 → 통과 (SecurityContext 미설정, 후속 JWT 필터가 처리)")
    void access_valid_passes_through_without_setting_context() throws Exception {
        String validAccess = jwtTokenProvider.createAccess(USER_ID, EMAIL, ROLES);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, validAccess));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        // SilentRefreshFilter는 SecurityContext 미설정 (후속 JWT 필터에 위임)
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 새 access 쿠키 미설정
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("access 쿠키 없음 → 통과 (refresh 시도 없음)")
    void no_access_cookie_passes_through() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("access 만료 + refresh store hash 불일치(탈퇴/재사용) → 미인증")
    void access_expired_refresh_hash_mismatch_is_unauthenticated() throws Exception {
        String expiredAccess = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);
        String refreshToken = jwtTokenProvider.createRefresh(USER_ID);
        // store에 다른 token 저장 (hash 불일치)
        fakeRefreshTokenStore.storeRefresh(USER_ID, "different-refresh-token", Duration.ofDays(14));

        Thread.sleep(10);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess),
                new Cookie(AuthCookies.REFRESH_TOKEN, refreshToken)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("access 만료 + refresh 쿠키 없음 → 미인증")
    void access_expired_no_refresh_cookie_is_unauthenticated() throws Exception {
        String expiredAccess = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);

        Thread.sleep(10);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        silentRefreshFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("동일 refresh 쿠키로 2회 연속 무음 refresh — 두 번 모두 성공(store 불변, 다중 탭 멱등)")
    void silent_refresh_twice_with_same_refresh_both_succeed() throws Exception {
        // access 즉시 만료, refresh 장기 유효
        String expiredAccess = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);
        String refreshToken = jwtTokenProvider.createRefresh(USER_ID);
        fakeRefreshTokenStore.storeRefresh(USER_ID, refreshToken, Duration.ofDays(14));

        Thread.sleep(10);

        // 1회 무음 refresh
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess),
                new Cookie(AuthCookies.REFRESH_TOKEN, refreshToken)
        );
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        silentRefreshFilter.doFilter(request1, response1, mock(FilterChain.class));

        // 1회 성공 확인
        List<String> setCookies1 = response1.getHeaders("Set-Cookie");
        assertThat(setCookies1).anyMatch(h -> h.startsWith(AuthCookies.ACCESS_TOKEN + "="));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        // SecurityContext 초기화 후 2회 시도
        SecurityContextHolder.clearContext();

        // 2회 무음 refresh — 동일 refresh 쿠키, 새로 만료된 access
        String expiredAccess2 = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);
        Thread.sleep(10);

        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess2),
                new Cookie(AuthCookies.REFRESH_TOKEN, refreshToken)  // 동일 refresh
        );
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        silentRefreshFilter.doFilter(request2, response2, mock(FilterChain.class));

        // 2회도 성공 — store가 불변이라 hash 비교 통과
        List<String> setCookies2 = response2.getHeaders("Set-Cookie");
        assertThat(setCookies2).anyMatch(h -> h.startsWith(AuthCookies.ACCESS_TOKEN + "="));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("무음 refresh 후 원래 refresh로 matchesRefresh = true (refresh hash 미변경)")
    void after_silent_refresh_original_refresh_still_matches() throws Exception {
        String expiredAccess = shortLivedProvider.createAccess(USER_ID, EMAIL, ROLES);
        String refreshToken = jwtTokenProvider.createRefresh(USER_ID);
        fakeRefreshTokenStore.storeRefresh(USER_ID, refreshToken, Duration.ofDays(14));

        Thread.sleep(10);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookies.ACCESS_TOKEN, expiredAccess),
                new Cookie(AuthCookies.REFRESH_TOKEN, refreshToken)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        silentRefreshFilter.doFilter(request, response, mock(FilterChain.class));

        // 무음 refresh 완료 후 원래 refresh token으로 matchesRefresh 여전히 true
        assertThat(fakeRefreshTokenStore.matchesRefresh(USER_ID, refreshToken)).isTrue();
    }
}
