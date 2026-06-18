package com.shop.shop.member.service;

import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.security.AuthTokenIssuer;
import com.shop.shop.security.JwtProperties;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ViewAuthService 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>login 성공 → IssuedTokens 반환 + authTokenIssuer.issue + memberService.recordLoginByEmail 호출</li>
 *   <li>login 실패 → InvalidCredentialsException 전파</li>
 *   <li>revoke(유효 access token) → deleteRefresh + blacklistAccess 호출</li>
 *   <li>revoke(null) → fail-safe (예외 미전파)</li>
 *   <li>revoke(만료 token) → fail-safe (예외 미전파)</li>
 * </ul>
 *
 * <p>쿠키 I/O(Set-Cookie, readAccess)는 CookieLoginViewController 책임이므로 이 테스트에서 검증하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ViewAuthServiceTest {

    private static final String SECRET = "test-secret-key-for-view-auth-service-32chars!!";
    private static final String EMAIL = "view-user@example.com";
    private static final long USER_ID = 10L;

    @Mock
    private MemberService memberService;

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;
    private FakeRefreshTokenStore fakeRefreshTokenStore;
    private AuthTokenIssuer authTokenIssuer;
    private ViewAuthService viewAuthService;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test");
        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        fakeRefreshTokenStore = new FakeRefreshTokenStore();
        authTokenIssuer = new AuthTokenIssuer(jwtTokenProvider, fakeRefreshTokenStore, jwtProperties);

        viewAuthService = new ViewAuthService(
                memberService, authTokenIssuer, jwtTokenProvider, fakeRefreshTokenStore);

        testUser = User.of(EMAIL, "hashed_pw", "뷰 사용자", null, Role.CONSUMER);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, USER_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ───────────────────────────── login ─────────────────────────────

    @Test
    @DisplayName("login 성공 → IssuedTokens 반환(accessToken·refreshToken 비어있지 않음)")
    void login_success_returns_issued_tokens() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        AuthTokenIssuer.IssuedTokens issued = viewAuthService.login(EMAIL, "password");

        assertThat(issued).isNotNull();
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("login 성공 → memberService.recordLoginByEmail 호출(last_login_at 갱신)")
    void login_success_records_login_activity() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        viewAuthService.login(EMAIL, "password");

        verify(memberService).recordLoginByEmail(EMAIL);
    }

    @Test
    @DisplayName("login 성공 → refresh token이 store에 저장된다(authTokenIssuer.issue 경유)")
    void login_success_stores_refresh_token() {
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);

        AuthTokenIssuer.IssuedTokens issued = viewAuthService.login(EMAIL, "password");

        assertThat(fakeRefreshTokenStore.matchesRefresh(USER_ID, issued.refreshToken())).isTrue();
    }

    @Test
    @DisplayName("login 실패 → InvalidCredentialsException 전파")
    void login_failure_propagates_exception() {
        when(memberService.authenticate(EMAIL, "wrong")).thenThrow(new InvalidCredentialsException());

        assertThatThrownBy(() -> viewAuthService.login(EMAIL, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ───────────────────────────── revoke ─────────────────────────────

    @Test
    @DisplayName("revoke(유효 access token) → deleteRefresh + blacklistAccess 호출")
    void revoke_with_valid_token_calls_store_operations() {
        // 로그인하여 토큰 발급
        when(memberService.authenticate(EMAIL, "password")).thenReturn(testUser);
        AuthTokenIssuer.IssuedTokens issued = viewAuthService.login(EMAIL, "password");

        String accessToken = issued.accessToken();
        String refreshToken = issued.refreshToken();
        String jti = jwtTokenProvider.extractJti(jwtTokenProvider.parse(accessToken));

        // revoke 전 상태 확인
        assertThat(fakeRefreshTokenStore.matchesRefresh(USER_ID, refreshToken)).isTrue();
        assertThat(fakeRefreshTokenStore.isBlacklisted(jti)).isFalse();

        // revoke 수행
        viewAuthService.revoke(accessToken);

        // deleteRefresh → refresh hash 삭제
        assertThat(fakeRefreshTokenStore.matchesRefresh(USER_ID, refreshToken)).isFalse();
        // blacklistAccess → access jti 등록
        assertThat(fakeRefreshTokenStore.isBlacklisted(jti)).isTrue();
    }

    @Test
    @DisplayName("revoke(null) → fail-safe (예외 미전파, store 작업 없음)")
    void revoke_with_null_token_is_fail_safe() {
        // 예외 미전파
        viewAuthService.revoke(null);
        // store 비어있음 (작업 없음) — 특별한 assertion 없이 예외 없이 통과하면 pass
    }

    @Test
    @DisplayName("revoke(만료 token) → fail-safe (예외 미전파)")
    void revoke_with_expired_token_is_fail_safe() {
        JwtProperties shortProps = new JwtProperties(SECRET, Duration.ofMillis(1), Duration.ofDays(14), "test");
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortProps);
        String expiredAccess = shortProvider.createAccess(USER_ID, EMAIL, List.of("ROLE_CONSUMER"));

        // 만료 대기
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 예외 미전파
        viewAuthService.revoke(expiredAccess);
    }
}
