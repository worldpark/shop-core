package com.shop.shop.member.service;

import com.shop.shop.common.exception.InvalidTokenException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.LoginRequest;
import com.shop.shop.member.dto.MeResponse;
import com.shop.shop.member.dto.RefreshRequest;
import com.shop.shop.member.dto.TokenResponse;
import com.shop.shop.security.JwtProperties;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * AuthServiceResponse 단위 테스트.
 * FakeRefreshTokenStore (인메모리), JwtTokenProvider (실 구현), MemberService (mock).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceResponseTest {

    private static final String SECRET = "test-secret-key-for-auth-service-response-32chars";
    private static final String EMAIL = "user@example.com";

    @Mock
    private MemberService memberService;

    private JwtTokenProvider jwtTokenProvider;
    private FakeRefreshTokenStore refreshTokenStore;
    private JwtProperties jwtProperties;
    private AuthServiceResponse authServiceResponse;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test");
        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        refreshTokenStore = new FakeRefreshTokenStore();
        authServiceResponse = new AuthServiceResponse(
                memberService, jwtTokenProvider, refreshTokenStore, jwtProperties);

        testUser = User.of(EMAIL, "hashed_pw", "테스터", null, Role.CONSUMER);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("login — access/refresh 토큰 발급 + Redis store 저장")
    void login_issues_tokens_and_stores_refresh() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        TokenResponse response = authServiceResponse.login(new LoginRequest(EMAIL, "password"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(jwtProperties.accessTtl().toSeconds());

        // refresh가 store에 저장되었는지 확인
        assertThat(refreshTokenStore.matchesRefresh(1L, response.refreshToken())).isTrue();
    }

    @Test
    @DisplayName("refresh — 유효한 refresh token으로 새 access token 발급")
    void refresh_with_valid_token_issues_new_access() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);
        when(memberService.getById(1L)).thenReturn(testUser);

        TokenResponse loginResponse = authServiceResponse.login(new LoginRequest(EMAIL, "password"));
        String refreshToken = loginResponse.refreshToken();

        TokenResponse refreshResponse = authServiceResponse.refresh(new RefreshRequest(refreshToken));

        assertThat(refreshResponse.accessToken()).isNotBlank();
        // refresh 유지 (회전 없음)
        assertThat(refreshResponse.refreshToken()).isEqualTo(refreshToken);
    }

    @Test
    @DisplayName("logout 후 동일 refresh token으로 재발급 시 InvalidTokenException (재사용 차단)")
    void logout_then_refresh_reuse_fails() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        TokenResponse loginResponse = authServiceResponse.login(new LoginRequest(EMAIL, "password"));
        String accessToken = loginResponse.accessToken();
        String refreshToken = loginResponse.refreshToken();

        // logout
        authServiceResponse.logout("Bearer " + accessToken);

        // 동일 refresh로 재발급 시도
        assertThatThrownBy(() -> authServiceResponse.refresh(new RefreshRequest(refreshToken)))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("logout 후 동일 access token은 blacklist에 등록된다")
    void logout_blacklists_access_token() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        TokenResponse loginResponse = authServiceResponse.login(new LoginRequest(EMAIL, "password"));
        String accessToken = loginResponse.accessToken();

        // jti 추출 (parse는 InvalidTokenException을 던질 수 있으므로 try-catch 없이 직접 호출)
        String jti = jwtTokenProvider.extractJti(jwtTokenProvider.parse(accessToken));

        assertThat(refreshTokenStore.isBlacklisted(jti)).isFalse();

        authServiceResponse.logout("Bearer " + accessToken);

        assertThat(refreshTokenStore.isBlacklisted(jti)).isTrue();
    }

    @Test
    @DisplayName("me — Authentication에서 userId 추출 후 MeResponse 반환")
    void me_returns_me_response() {
        when(memberService.getById(1L)).thenReturn(testUser);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));

        MeResponse response = authServiceResponse.me(authentication);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.role()).isEqualTo("CONSUMER");
    }
}
