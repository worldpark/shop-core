package com.shop.shop.security;

import com.shop.shop.common.config.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisPasswordResetTokenStore 단위 테스트 (Mock StringRedisTemplate).
 *
 * <p>검증 범위:
 * - store: resetPrefix + sha256(token) 키 사용, value=userId(원문 미저장)
 * - peek: get 호출, getAndDelete 미호출
 * - consume: getAndDelete 호출 (GETDEL 원자)
 * - 키가 resetPrefix + sha256(token) 형태
 */
@ExtendWith(MockitoExtension.class)
class RedisPasswordResetTokenStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisPasswordResetTokenStore tokenStore;
    private RedisProperties redisProperties;

    private static final String RESET_PREFIX = "shopcore:auth:reset:";
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties(null, null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        tokenStore = new RedisPasswordResetTokenStore(redisTemplate, redisProperties);
    }

    @Test
    @DisplayName("store: resetPrefix + sha256(token) 키, value=userId, ttl 설정")
    void store_usesResetPrefixAndSha256Key() {
        String token = "plaintexttoken123";
        Duration ttl = Duration.ofMinutes(30);

        tokenStore.store(token, USER_ID, ttl);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), eq(ttl));

        String key = keyCaptor.getValue();
        assertThat(key).startsWith(RESET_PREFIX);
        assertThat(key).isNotEqualTo(RESET_PREFIX + token);   // 원문 미저장
        assertThat(key.length()).isGreaterThan(RESET_PREFIX.length() + 10);  // sha256 존재

        assertThat(valueCaptor.getValue()).isEqualTo(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("peek: get 호출(비소비), userId Optional 반환")
    void peek_usesGetNotDelete() {
        String token = "peektoken";
        when(valueOps.get(startsWith(RESET_PREFIX))).thenReturn(String.valueOf(USER_ID));

        Optional<Long> result = tokenStore.peek(token);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("peek: 미존재 키 → empty Optional")
    void peek_absent_returnsEmpty() {
        String token = "unknowntoken";
        when(valueOps.get(startsWith(RESET_PREFIX))).thenReturn(null);

        Optional<Long> result = tokenStore.peek(token);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("consume: getAndDelete 호출(GETDEL 원자), userId Optional 반환")
    void consume_usesGetAndDelete() {
        String token = "consumetoken";
        when(valueOps.getAndDelete(startsWith(RESET_PREFIX))).thenReturn(String.valueOf(USER_ID));

        Optional<Long> result = tokenStore.consume(token);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("consume: 미존재/만료 키 → empty Optional")
    void consume_absent_returnsEmpty() {
        String token = "expiredtoken";
        when(valueOps.getAndDelete(startsWith(RESET_PREFIX))).thenReturn(null);

        Optional<Long> result = tokenStore.consume(token);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("동일 토큰 store 2회: 키가 동일(sha256 결정론적)")
    void store_sameToken_producesSameKey() {
        String token = "sametoken";
        Duration ttl = Duration.ofMinutes(30);

        tokenStore.store(token, USER_ID, ttl);
        tokenStore.store(token, USER_ID + 1, ttl);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2))
                .set(keyCaptor.capture(), org.mockito.ArgumentMatchers.anyString(), eq(ttl));

        assertThat(keyCaptor.getAllValues().get(0))
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }

    /** ArgumentMatchers.startsWith 별칭 */
    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.startsWith(prefix);
    }
}
