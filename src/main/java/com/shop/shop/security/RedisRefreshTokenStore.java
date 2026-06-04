package com.shop.shop.security;

import com.shop.shop.common.config.RedisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Redis 기반 RefreshTokenStore 구현.
 *
 * <p>refresh 저장: key={refreshPrefix}{userId}, value=SHA-256(refreshToken), TTL=refreshTtl
 * <p>blacklist: key={blacklistPrefix}{jti}, value="1", TTL=access 잔여 만료 시간
 *
 * <p>prefix 출처: RedisProperties.Auth (shopcore:auth:refresh: / shopcore:auth:blacklist:)
 * <p>TTL 출처: JwtProperties (단일 소스) — RedisProperties.*-ttl은 namespace 설명용, 코드 미참조
 *
 * <p>StringRedisTemplate 제공 방식:
 *   운영: Spring Boot RedisAutoConfiguration이 StringRedisTemplate을 자동 제공한다.
 *   테스트: RedisAutoConfiguration이 활성화되어 Lettuce 기반 StringRedisTemplate 빈이 생성된다
 *          (Lettuce는 지연 연결이므로 Redis 브로커 없이도 빈 생성 및 컨텍스트 로드 통과).
 *          동작 격리는 FakeRefreshTokenStore(@Primary)가 담당하므로 실제 Redis 명령은 실행되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String BLACKLIST_VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public void storeRefresh(long userId, String refreshToken, Duration ttl) {
        String key = refreshKey(userId);
        String hash = sha256(refreshToken);
        redisTemplate.opsForValue().set(key, hash, ttl);
        log.debug("refresh 저장: userId={}, ttl={}", userId, ttl);
    }

    @Override
    public boolean matchesRefresh(long userId, String refreshToken) {
        String key = refreshKey(userId);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            return false;
        }
        return stored.equals(sha256(refreshToken));
    }

    @Override
    public void deleteRefresh(long userId) {
        String key = refreshKey(userId);
        redisTemplate.delete(key);
        log.debug("refresh 삭제: userId={}", userId);
    }

    @Override
    public void blacklistAccess(String jti, Duration remainingTtl) {
        if (remainingTtl.isZero() || remainingTtl.isNegative()) {
            log.debug("blacklist 등록 생략 (이미 만료): jti={}", jti);
            return;
        }
        String key = blacklistKey(jti);
        redisTemplate.opsForValue().set(key, BLACKLIST_VALUE, remainingTtl);
        log.debug("blacklist 등록: jti={}, ttl={}", jti, remainingTtl);
    }

    @Override
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey(jti)));
    }

    private String refreshKey(long userId) {
        return redisProperties.auth().refreshPrefix() + userId;
    }

    private String blacklistKey(String jti) {
        return redisProperties.auth().blacklistPrefix() + jti;
    }

    /**
     * SHA-256 hash 계산.
     * refresh token 원문을 Redis에 직접 저장하지 않고 hash를 저장한다 (Constraint).
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘 사용 불가", e);
        }
    }
}
