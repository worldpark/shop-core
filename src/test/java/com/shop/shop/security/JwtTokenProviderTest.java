package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtTokenProvider 단위 테스트.
 * 실 Spring 컨텍스트 없이 JwtProperties만 직접 주입.
 * member 도메인 타입(User) 비의존 — security 모듈 순환 의존 차단.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-unit-test-32chars";
    private static final String ISSUER = "test-issuer";

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                SECRET,
                Duration.ofMinutes(30),
                Duration.ofDays(14),
                ISSUER
        );
        jwtTokenProvider = new JwtTokenProvider(properties);
    }

    @Test
    @DisplayName("access token 발급 및 클레임 검증 — sub/email/roles/jti/iss 포함")
    void createAccess_contains_required_claims() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        Claims claims = jwtTokenProvider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("email", String.class)).isEqualTo("user@example.com");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    @DisplayName("access token은 roles 클레임을 포함한다")
    void createAccess_contains_roles_claim() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        Claims claims = jwtTokenProvider.parse(token);

        List<String> roles = jwtTokenProvider.extractRoles(claims);
        assertThat(roles).containsExactly("ROLE_CONSUMER");
    }

    @Test
    @DisplayName("refresh token 발급 및 클레임 검증 — sub/jti/iss 포함, roles 미포함")
    void createRefresh_does_not_contain_roles_claim() {
        String token = jwtTokenProvider.createRefresh(1L);
        Claims claims = jwtTokenProvider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getId()).isNotBlank();

        // refresh token은 roles 클레임을 포함하지 않는다
        assertThat(claims.get("roles")).isNull();
    }

    @Test
    @DisplayName("만료된 토큰 파싱 시 InvalidTokenException 발생")
    void parse_expired_token_throws_InvalidTokenException() {
        JwtProperties shortTtlProps = new JwtProperties(
                SECRET,
                Duration.ofMillis(1),  // 1ms — 즉시 만료
                Duration.ofDays(14),
                ISSUER
        );
        JwtTokenProvider shortTtlProvider = new JwtTokenProvider(shortTtlProps);

        String token = shortTtlProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));

        // 만료 대기
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {}

        assertThatThrownBy(() -> jwtTokenProvider.parse(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("서명이 위조된 토큰 파싱 시 InvalidTokenException 발생")
    void parse_tampered_token_throws_InvalidTokenException() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        String tamperedToken = token + "tampered";

        assertThatThrownBy(() -> jwtTokenProvider.parse(tamperedToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰 파싱 시 InvalidTokenException 발생")
    void parse_malformed_token_throws_InvalidTokenException() {
        assertThatThrownBy(() -> jwtTokenProvider.parse("not.a.valid.jwt"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("extractUserId — access token sub에서 userId를 정확히 추출")
    void extractUserId_returns_correct_value() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        Claims claims = jwtTokenProvider.parse(token);

        assertThat(jwtTokenProvider.extractUserId(claims)).isEqualTo(1L);
    }

    @Test
    @DisplayName("extractJti — access token jti를 정확히 추출")
    void extractJti_returns_non_blank_uuid() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        Claims claims = jwtTokenProvider.parse(token);

        String jti = jwtTokenProvider.extractJti(claims);
        assertThat(jti).isNotBlank();
    }

    @Test
    @DisplayName("remainingTtl — 유효 토큰의 잔여 만료 시간은 양수")
    void remainingTtl_is_positive_for_valid_token() {
        String token = jwtTokenProvider.createAccess(1L, "user@example.com", List.of("ROLE_CONSUMER"));
        Claims claims = jwtTokenProvider.parse(token);

        Duration remaining = jwtTokenProvider.remainingTtl(claims);
        assertThat(remaining).isPositive();
    }

    @Test
    @DisplayName("ADMIN 사용자 access token의 roles는 ROLE_ADMIN이다")
    void admin_user_has_ROLE_ADMIN_authority() {
        String token = jwtTokenProvider.createAccess(2L, "admin@example.com", List.of("ROLE_ADMIN"));
        Claims claims = jwtTokenProvider.parse(token);

        assertThat(jwtTokenProvider.extractRoles(claims)).containsExactly("ROLE_ADMIN");
    }
}
