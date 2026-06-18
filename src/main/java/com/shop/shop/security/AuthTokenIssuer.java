package com.shop.shop.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API·View 공용 토큰 발급 코어.
 *
 * <p>입력: userId, email, roles. 출력: {@link IssuedTokens}(accessToken, refreshToken, accessTtlSeconds).
 * 내부에서 createAccess + createRefresh + storeRefresh(refreshTtl)를 1곳에 수행한다.
 *
 * <p>전달 매체만 호출자가 결정한다:
 * <ul>
 *   <li>API: {@link IssuedTokens} → TokenResponse JSON 바디</li>
 *   <li>View: {@link IssuedTokens} → Set-Cookie(access_token/refresh_token)</li>
 * </ul>
 *
 * <p>발급 로직 중복 0 — AuthServiceResponse와 ViewAuthService가 모두 이 컴포넌트를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenIssuer {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;

    /**
     * access + refresh 토큰 발급 및 refresh Redis 저장.
     *
     * @param userId 회원 식별자
     * @param email  회원 이메일 (access 클레임에 포함)
     * @param roles  권한 목록 (예: ["ROLE_CONSUMER"])
     * @return 발급된 토큰 및 access TTL을 담은 값 객체
     */
    public IssuedTokens issue(long userId, String email, List<String> roles) {
        String accessToken = jwtTokenProvider.createAccess(userId, email, roles);
        String refreshToken = jwtTokenProvider.createRefresh(userId);

        refreshTokenStore.storeRefresh(userId, refreshToken, jwtProperties.refreshTtl());

        long accessTtlSeconds = jwtProperties.accessTtl().toSeconds();
        log.debug("토큰 발급: userId={}, roles={}", userId, roles);
        return new IssuedTokens(accessToken, refreshToken, accessTtlSeconds);
    }

    /**
     * access token 만 발급한다. refresh 생성·storeRefresh 호출 없음.
     *
     * <p>무음(silent) refresh 전용 경로 — refresh 토큰·Redis store를 건드리지 않아
     * 다중 탭 동시 재발급 멱등(§1.4)이 보장된다.
     *
     * @param userId 회원 식별자
     * @param email  회원 이메일 (access 클레임에 포함)
     * @param roles  권한 목록 (예: ["ROLE_CONSUMER"])
     * @return 새 access token 문자열
     */
    public String issueAccessOnly(long userId, String email, List<String> roles) {
        String accessToken = jwtTokenProvider.createAccess(userId, email, roles);
        log.debug("access only 발급(refresh 불변): userId={}", userId);
        return accessToken;
    }

    /**
     * 발급된 토큰 및 TTL 값 객체.
     *
     * @param accessToken      JWT access token 문자열
     * @param refreshToken     JWT refresh token 문자열
     * @param accessTtlSeconds access token 만료 시간(초)
     */
    public record IssuedTokens(String accessToken, String refreshToken, long accessTtlSeconds) {
    }
}
