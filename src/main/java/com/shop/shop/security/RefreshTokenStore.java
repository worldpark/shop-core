package com.shop.shop.security;

import java.time.Duration;

/**
 * Refresh token / access token blacklist 저장 포트 인터페이스.
 * 운영 구현: RedisRefreshTokenStore (StringRedisTemplate).
 * 테스트 구현: FakeRefreshTokenStore (인메모리 Map).
 */
public interface RefreshTokenStore {

    /**
     * 로그인 시 refresh token을 userId 기준으로 저장.
     * 저장값은 SHA-256 hash (원문 저장 금지 — Constraint).
     *
     * @param userId       회원 식별자
     * @param refreshToken refresh token 원문 (내부에서 hash 처리)
     * @param ttl          키 만료 시간 (JwtProperties.refreshTtl 사용)
     */
    void storeRefresh(long userId, String refreshToken, Duration ttl);

    /**
     * 제시된 refresh token이 저장된 hash와 일치하는지 검증.
     *
     * @param userId       회원 식별자
     * @param refreshToken refresh token 원문
     * @return hash 일치 여부 (false: 부재/불일치 → 재사용/탈취 차단)
     */
    boolean matchesRefresh(long userId, String refreshToken);

    /**
     * logout 시 refresh token 삭제.
     *
     * @param userId 회원 식별자
     */
    void deleteRefresh(long userId);

    /**
     * logout 시 access token의 jti를 blacklist에 등록.
     * TTL = access token 잔여 만료 시간 (동적 산정 — JwtTokenProvider.remainingTtl).
     * TTL이 0 이하이면 이미 만료된 토큰이므로 등록 생략.
     *
     * @param jti          access token JWT ID
     * @param remainingTtl 잔여 만료 시간
     */
    void blacklistAccess(String jti, Duration remainingTtl);

    /**
     * 요청 access token의 jti가 blacklist에 있는지 조회.
     *
     * @param jti access token JWT ID
     * @return blacklist 등록 여부
     */
    boolean isBlacklisted(String jti);
}
