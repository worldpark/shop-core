package com.shop.shop.security;

import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefreshTokenStore 단위 테스트.
 * FakeRefreshTokenStore (인메모리)로 Redis 없이 검증.
 * RedisRefreshTokenStore와 동일 계약을 검증한다.
 */
class RefreshTokenStoreTest {

    private FakeRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new FakeRefreshTokenStore();
    }

    @Test
    @DisplayName("storeRefresh 후 matchesRefresh — 동일 토큰: true")
    void store_and_match_same_token() {
        store.storeRefresh(1L, "refresh-token-abc", Duration.ofDays(14));
        assertThat(store.matchesRefresh(1L, "refresh-token-abc")).isTrue();
    }

    @Test
    @DisplayName("storeRefresh 후 matchesRefresh — 다른 토큰: false")
    void store_and_match_different_token() {
        store.storeRefresh(1L, "refresh-token-abc", Duration.ofDays(14));
        assertThat(store.matchesRefresh(1L, "different-token")).isFalse();
    }

    @Test
    @DisplayName("deleteRefresh 후 matchesRefresh — false (삭제됨)")
    void delete_then_match_returns_false() {
        store.storeRefresh(1L, "refresh-token-abc", Duration.ofDays(14));
        store.deleteRefresh(1L);
        assertThat(store.matchesRefresh(1L, "refresh-token-abc")).isFalse();
    }

    @Test
    @DisplayName("저장된 토큰 없음 — matchesRefresh: false")
    void match_without_store_returns_false() {
        assertThat(store.matchesRefresh(999L, "any-token")).isFalse();
    }

    @Test
    @DisplayName("blacklistAccess 후 isBlacklisted — true")
    void blacklist_then_isBlacklisted_true() {
        store.blacklistAccess("jti-uuid-123", Duration.ofMinutes(30));
        assertThat(store.isBlacklisted("jti-uuid-123")).isTrue();
    }

    @Test
    @DisplayName("blacklist 미등록 jti — isBlacklisted: false")
    void not_blacklisted_jti_returns_false() {
        assertThat(store.isBlacklisted("unknown-jti")).isFalse();
    }

    @Test
    @DisplayName("remainingTtl=0 이면 blacklist 등록 생략 — isBlacklisted: false")
    void blacklist_with_zero_ttl_is_skipped() {
        store.blacklistAccess("jti-zero-ttl", Duration.ZERO);
        assertThat(store.isBlacklisted("jti-zero-ttl")).isFalse();
    }

    @Test
    @DisplayName("remainingTtl 음수 이면 blacklist 등록 생략 — isBlacklisted: false")
    void blacklist_with_negative_ttl_is_skipped() {
        store.blacklistAccess("jti-neg-ttl", Duration.ofSeconds(-1));
        assertThat(store.isBlacklisted("jti-neg-ttl")).isFalse();
    }

    @Test
    @DisplayName("userId별 독립 저장 — 다른 userId의 토큰과 혼동 없음")
    void store_is_per_userId() {
        store.storeRefresh(1L, "token-user-1", Duration.ofDays(14));
        store.storeRefresh(2L, "token-user-2", Duration.ofDays(14));

        assertThat(store.matchesRefresh(1L, "token-user-1")).isTrue();
        assertThat(store.matchesRefresh(2L, "token-user-2")).isTrue();
        assertThat(store.matchesRefresh(1L, "token-user-2")).isFalse();
    }
}
