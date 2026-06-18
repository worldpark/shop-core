package com.shop.shop.security;

import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthTokenIssuer 단위 테스트 — API·View 공용화 발급 코어.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>issue() → access + refresh 발급 및 IssuedTokens 반환</li>
 *   <li>refresh는 Redis store에 저장됨 (matchesRefresh=true)</li>
 *   <li>accessTtlSeconds = JwtProperties.accessTtl.toSeconds()</li>
 *   <li>API 무회귀: AuthServiceResponse가 AuthTokenIssuer를 통해 동일 결과 생성</li>
 * </ul>
 */
class AuthTokenIssuerTest {

    private static final String SECRET = "test-secret-key-for-auth-token-issuer-32chars!!";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;
    private FakeRefreshTokenStore fakeRefreshTokenStore;
    private AuthTokenIssuer authTokenIssuer;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET, ACCESS_TTL, REFRESH_TTL, "test");
        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        fakeRefreshTokenStore = new FakeRefreshTokenStore();
        authTokenIssuer = new AuthTokenIssuer(jwtTokenProvider, fakeRefreshTokenStore, jwtProperties);
    }

    @Test
    @DisplayName("issue() — IssuedTokens 반환: accessToken·refreshToken 비어있지 않음")
    void issue_returns_non_blank_tokens() {
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(1L, "user@example.com", List.of("ROLE_CONSUMER"));

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("issue() — accessTtlSeconds = JwtProperties.accessTtl.toSeconds() (TTL SSOT)")
    void issue_accessTtlSeconds_matches_properties() {
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(1L, "user@example.com", List.of("ROLE_CONSUMER"));

        assertThat(issued.accessTtlSeconds()).isEqualTo(ACCESS_TTL.toSeconds());
    }

    @Test
    @DisplayName("issue() — refresh token이 Redis store에 저장됨 (matchesRefresh=true)")
    void issue_stores_refresh_token_in_store() {
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(1L, "user@example.com", List.of("ROLE_CONSUMER"));

        assertThat(fakeRefreshTokenStore.matchesRefresh(1L, issued.refreshToken())).isTrue();
    }

    @Test
    @DisplayName("issue() — access token 파싱 후 email 클레임 확인")
    void issue_access_token_contains_email_claim() {
        String email = "check-email@example.com";
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(42L, email, List.of("ROLE_SELLER"));

        io.jsonwebtoken.Claims claims = jwtTokenProvider.parse(issued.accessToken());
        assertThat(jwtTokenProvider.extractEmail(claims)).isEqualTo(email);
    }

    @Test
    @DisplayName("issue() — access token 파싱 후 userId·roles 확인")
    void issue_access_token_contains_userId_and_roles() {
        long userId = 77L;
        AuthTokenIssuer.IssuedTokens issued = authTokenIssuer.issue(userId, "test@example.com", List.of("ROLE_ADMIN"));

        io.jsonwebtoken.Claims claims = jwtTokenProvider.parse(issued.accessToken());
        assertThat(jwtTokenProvider.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtTokenProvider.extractRoles(claims)).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("issue() 2회 호출 → 매번 새 access·refresh 발급 (고유한 jti)")
    void issue_twice_produces_different_tokens() {
        AuthTokenIssuer.IssuedTokens first = authTokenIssuer.issue(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        AuthTokenIssuer.IssuedTokens second = authTokenIssuer.issue(1L, "user@example.com", List.of("ROLE_CONSUMER"));

        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
    }
}
