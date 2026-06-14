package com.shop.shop.security;

import com.shop.shop.common.config.RedisProperties;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisPasswordResetTokenStore 운영 구현 통합 테스트 (실 Redis Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>store 후 키 {@code shopcore:auth:reset:{sha256(token)}} 존재 + value=userId + TTL > 0</li>
 *   <li>peek(token) → userId 반환 + 키 미삭제 (비소비·GET 안전)</li>
 *   <li>consume(token) → userId 반환 후 키 삭제 (1회용·재소비 empty)</li>
 *   <li>저장 값이 토큰 원문이 아니라 userId (원문 미저장 단언)</li>
 *   <li>짧은 TTL 경과 후 peek/consume empty (만료 단언)</li>
 * </ul>
 *
 * <p>의존성 추가 없이 {@code GenericContainer}로 {@code redis:7-alpine}을 띄우고
 * {@link DynamicPropertySource}로 {@code spring.data.redis.host/port}를 주입한다.
 * FakePasswordResetTokenStore를 @Import하지 않고 실 구현(RedisPasswordResetTokenStore)·
 * 실 StringRedisTemplate을 사용한다.
 *
 * <p>전체 Spring Boot 컨텍스트(JPA·Flyway 포함)가 필요하므로 PostgreSQL도 Testcontainers로 제공한다.
 * RefreshTokenStore는 FakeRefreshTokenStore를 @Import하여 Redis RefreshToken 의존을 제거한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(FakeRefreshTokenStore.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PasswordResetRedisIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RedisPasswordResetTokenStore tokenStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    private static final long USER_ID = 777L;
    private static final String RESET_PREFIX = "shopcore:auth:reset:";

    @BeforeEach
    void setUp() {
        // 테스트 격리: 재설정 토큰 네임스페이스 전체 초기화
        var keys = stringRedisTemplate.keys(RESET_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        var keys = stringRedisTemplate.keys(RESET_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ============================================================
    // store — 키 존재 + value=userId + TTL > 0
    // ============================================================

    @Test
    @DisplayName("store 후 키 shopcore:auth:reset:{sha256(token)} 존재 + value=userId + TTL > 0")
    void store_키존재_value는userId_TTL양수() {
        String token = "integrationtoken001";
        Duration ttl = Duration.ofMinutes(30);

        tokenStore.store(token, USER_ID, ttl);

        String expectedKey = RESET_PREFIX + sha256(token);

        // 키 존재 확인
        assertThat(stringRedisTemplate.hasKey(expectedKey)).isTrue();

        // value = userId (원문 토큰이 아님)
        String storedValue = stringRedisTemplate.opsForValue().get(expectedKey);
        assertThat(storedValue).isEqualTo(String.valueOf(USER_ID));
        assertThat(storedValue).isNotEqualTo(token); // 원문 미저장 단언

        // TTL > 0 (약 30분)
        Long ttlSeconds = stringRedisTemplate.getExpire(expectedKey);
        assertThat(ttlSeconds).isNotNull().isPositive();
    }

    // ============================================================
    // peek — userId 반환 + 키 미삭제 (비소비)
    // ============================================================

    @Test
    @DisplayName("peek(token) → userId 반환하고 키 미삭제 (비소비 — GET 안전)")
    void peek_userId반환_키미삭제() {
        String token = "integrationtoken002";
        tokenStore.store(token, USER_ID, Duration.ofMinutes(30));

        Optional<Long> result = tokenStore.peek(token);

        // userId 반환
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(USER_ID);

        // 키 미삭제 (peek 후에도 키 존속)
        String key = RESET_PREFIX + sha256(token);
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();
    }

    @Test
    @DisplayName("peek — 미존재 토큰 → empty")
    void peek_미존재토큰_empty() {
        Optional<Long> result = tokenStore.peek("nonexistenttoken");
        assertThat(result).isEmpty();
    }

    // ============================================================
    // consume — userId 반환 후 키 삭제 (1회용)
    // ============================================================

    @Test
    @DisplayName("consume(token) → userId 반환 후 키 삭제 (1회용·재소비 empty)")
    void consume_userId반환_키삭제_재소비불가() {
        String token = "integrationtoken003";
        tokenStore.store(token, USER_ID, Duration.ofMinutes(30));

        // 1회 consume
        Optional<Long> result = tokenStore.consume(token);

        // userId 반환
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(USER_ID);

        // 키 삭제 확인
        String key = RESET_PREFIX + sha256(token);
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();

        // 재소비 불가 (empty)
        Optional<Long> reConsumeResult = tokenStore.consume(token);
        assertThat(reConsumeResult).isEmpty();

        // peek도 empty
        Optional<Long> peekResult = tokenStore.peek(token);
        assertThat(peekResult).isEmpty();
    }

    @Test
    @DisplayName("consume — 미존재 토큰 → empty")
    void consume_미존재토큰_empty() {
        Optional<Long> result = tokenStore.consume("nonexistenttoken");
        assertThat(result).isEmpty();
    }

    // ============================================================
    // peek 후 consume 연속 — 키 존속 → 소비 후 삭제
    // ============================================================

    @Test
    @DisplayName("peek 후 consume — peek은 키 미삭제, consume 후 키 삭제")
    void peek후consume_키존속후삭제() {
        String token = "integrationtoken004";
        tokenStore.store(token, USER_ID, Duration.ofMinutes(30));
        String key = RESET_PREFIX + sha256(token);

        // peek: 키 미삭제
        Optional<Long> peekResult = tokenStore.peek(token);
        assertThat(peekResult).isPresent();
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();

        // consume: 키 삭제
        Optional<Long> consumeResult = tokenStore.consume(token);
        assertThat(consumeResult).isPresent();
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }

    // ============================================================
    // TTL 만료 후 peek/consume empty
    // ============================================================

    @Test
    @DisplayName("짧은 TTL(1초) 경과 후 peek/consume empty (만료 확인)")
    void shortTtl_만료후_peek_consume_empty() throws InterruptedException {
        String token = "integrationtoken005";
        tokenStore.store(token, USER_ID, Duration.ofSeconds(1));

        // TTL 내: 유효
        assertThat(tokenStore.peek(token)).isPresent();

        // TTL 경과 대기
        Thread.sleep(1500);

        // 만료 후: empty
        assertThat(tokenStore.peek(token)).isEmpty();
        assertThat(tokenStore.consume(token)).isEmpty();
    }

    // ============================================================
    // 원문 미저장 단언 — value가 토큰 원문이 아닌 userId
    // ============================================================

    @Test
    @DisplayName("저장 값이 토큰 원문이 아니라 userId (원문 미저장)")
    void store_value는tokeOrigin이아님_userId임() {
        String token = "plaintexttoken999";
        tokenStore.store(token, USER_ID, Duration.ofMinutes(30));

        String key = RESET_PREFIX + sha256(token);
        String storedValue = stringRedisTemplate.opsForValue().get(key);

        // value = userId (Long을 String으로 직렬화한 값)
        assertThat(storedValue).isEqualTo(String.valueOf(USER_ID));
        // 원문 토큰과 다름
        assertThat(storedValue).isNotEqualTo(token);
    }

    // ============================================================
    // SHA-256 헬퍼 (RedisPasswordResetTokenStore와 동일 로직)
    // ============================================================

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘 사용 불가", e);
        }
    }
}
