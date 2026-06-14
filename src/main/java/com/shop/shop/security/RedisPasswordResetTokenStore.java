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
import java.util.Optional;

/**
 * Redis 기반 PasswordResetTokenStore 구현.
 *
 * <p>저장 방식:
 * <ul>
 *   <li>키: {@code {resetPrefix}{SHA-256(token)}} — 원문 미저장</li>
 *   <li>값: {@code String.valueOf(userId)}</li>
 *   <li>TTL: {@code RedisProperties.auth().resetTtl()} (기본 30분)</li>
 * </ul>
 *
 * <p>peek: {@code opsForValue().get(key)} — 키 삭제 없음(GET 안전, 비소비).
 * <p>consume: {@code opsForValue().getAndDelete(key)} — Redis GETDEL 원자 연산(1회용 보장).
 * <p>파싱 실패(value → Long): 일반 예외 전파(500 범주, 서버 오류 — 커스텀 예외 과설계 금지).
 *
 * <p>토큰 원문은 로그에 절대 기록하지 않는다.
 * RedisRefreshTokenStore.sha256 패턴을 동일하게 계승(HexFormat).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPasswordResetTokenStore implements PasswordResetTokenStore {

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;

    @Override
    public void store(String token, long userId, Duration ttl) {
        String key = resetKey(token);
        redisTemplate.opsForValue().set(key, String.valueOf(userId), ttl);
        log.debug("비밀번호 재설정 토큰 저장: userId={}, ttl={}", userId, ttl);
    }

    @Override
    public Optional<Long> peek(String token) {
        String key = resetKey(token);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    @Override
    public Optional<Long> consume(String token) {
        String key = resetKey(token);
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    private String resetKey(String token) {
        return redisProperties.auth().resetPrefix() + sha256(token);
    }

    /**
     * SHA-256 hash 계산.
     * RedisRefreshTokenStore.sha256와 동일 로직(HexFormat 사용).
     * 토큰 원문을 Redis에 직접 저장하지 않고 hash를 저장한다.
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
