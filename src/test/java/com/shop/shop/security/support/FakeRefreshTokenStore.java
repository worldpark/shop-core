package com.shop.shop.security.support;

import com.shop.shop.security.RefreshTokenStore;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 인메모리 RefreshTokenStore.
 * Redis 브로커 없이 RefreshTokenStore 인터페이스를 구현한다.
 *
 * <p>TTL 만료는 시뮬레이션하지 않는다 (단위/통합 테스트에서 TTL이 영향을 주지 않도록).
 * RedisRefreshTokenStore와 동일한 SHA-256 hash 로직을 사용한다.
 *
 * <p>@Import 전용 — @Component를 달지 않아 컴포넌트 스캔으로 자동 등록되지 않는다.
 * 이 Fake를 사용하는 테스트는 반드시 @Import(FakeRefreshTokenStore.class)로 명시해야 한다.
 * 배선 검증 테스트(@Import 없음)에서는 이 Fake가 배제되어 RedisRefreshTokenStore가 실제 빈으로 확인된다.
 *
 * <p>@Primary: 동일 컨텍스트에 RedisRefreshTokenStore가 함께 존재할 때 이 Fake가 우선 선택된다.
 */
@Primary
public class FakeRefreshTokenStore implements RefreshTokenStore {

    /** userId → SHA-256(refreshToken) */
    private final Map<Long, String> refreshStore = new ConcurrentHashMap<>();

    /** blacklist jti 집합 */
    private final Set<String> blacklistStore = ConcurrentHashMap.newKeySet();

    @Override
    public void storeRefresh(long userId, String refreshToken, Duration ttl) {
        refreshStore.put(userId, sha256(refreshToken));
    }

    @Override
    public boolean matchesRefresh(long userId, String refreshToken) {
        String stored = refreshStore.get(userId);
        if (stored == null) {
            return false;
        }
        return stored.equals(sha256(refreshToken));
    }

    @Override
    public void deleteRefresh(long userId) {
        refreshStore.remove(userId);
    }

    @Override
    public void blacklistAccess(String jti, Duration remainingTtl) {
        if (!remainingTtl.isZero() && !remainingTtl.isNegative()) {
            blacklistStore.add(jti);
        }
    }

    @Override
    public boolean isBlacklisted(String jti) {
        return blacklistStore.contains(jti);
    }

    /** 테스트용 상태 초기화 */
    public void clear() {
        refreshStore.clear();
        blacklistStore.clear();
    }

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
