package com.shop.shop.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisProperties 바인딩 단위 테스트.
 *
 * 검증 목표:
 * 1. yml/환경변수 미지정 시 기본값 폴백(shopcore:* prefix, Duration 기본값)
 * 2. @TestPropertySource 오버라이드 시 바인딩 값 반영
 * 3. prefix가 "shopcore:"로 시작(namespace 격리 회귀 방지)
 * 4. notification prefix("notif:")가 이 설정에 존재하지 않음(교차 namespace 참조 0)
 *
 * ApplicationContextRunner 사용: Redis 브로커 미기동 상태에서도 동작.
 * RedisAutoConfiguration은 포함하지 않아 연결 시도 없음.
 */
class RedisPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisPropertiesTestConfig.class);

    @Test
    @DisplayName("기본값 폴백: yml/환경변수 미지정 시 shopcore:* prefix와 Duration 기본값 사용")
    void defaultValues_fallback() {
        contextRunner.run(context -> {
            RedisProperties props = context.getBean(RedisProperties.class);

            assertThat(props.auth().refreshPrefix()).isEqualTo("shopcore:auth:refresh:");
            assertThat(props.auth().refreshTtl()).isEqualTo(Duration.ofDays(14));
            assertThat(props.auth().blacklistPrefix()).isEqualTo("shopcore:auth:blacklist:");
            assertThat(props.auth().blacklistTtl()).isEqualTo(Duration.ofMinutes(30));
            // 비밀번호 재설정 토큰 기본값 (Task 030)
            assertThat(props.auth().resetPrefix()).isEqualTo("shopcore:auth:reset:");
            assertThat(props.auth().resetTtl()).isEqualTo(Duration.ofMinutes(30));
            assertThat(props.lock().prefix()).isEqualTo("shopcore:lock:");
            assertThat(props.lock().ttl()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("오버라이드: 설정값 지정 시 바인딩 반영")
    void override_bindsCorrectly() {
        contextRunner
                .withPropertyValues(
                        "shop.redis.auth.refresh-prefix=shopcore:auth:refresh:custom:",
                        "shop.redis.auth.refresh-ttl=P7D",
                        "shop.redis.auth.blacklist-prefix=shopcore:auth:blacklist:custom:",
                        "shop.redis.auth.blacklist-ttl=PT15M",
                        "shop.redis.lock.prefix=shopcore:lock:custom:",
                        "shop.redis.lock.ttl=PT5S"
                )
                .run(context -> {
                    RedisProperties props = context.getBean(RedisProperties.class);

                    assertThat(props.auth().refreshPrefix()).isEqualTo("shopcore:auth:refresh:custom:");
                    assertThat(props.auth().refreshTtl()).isEqualTo(Duration.ofDays(7));
                    assertThat(props.auth().blacklistPrefix()).isEqualTo("shopcore:auth:blacklist:custom:");
                    assertThat(props.auth().blacklistTtl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(props.lock().prefix()).isEqualTo("shopcore:lock:custom:");
                    assertThat(props.lock().ttl()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("namespace 격리: 모든 prefix가 shopcore: 로 시작")
    void allPrefixes_startWithShopcore() {
        contextRunner.run(context -> {
            RedisProperties props = context.getBean(RedisProperties.class);

            assertThat(props.auth().refreshPrefix()).startsWith("shopcore:");
            assertThat(props.auth().blacklistPrefix()).startsWith("shopcore:");
            assertThat(props.auth().resetPrefix()).startsWith("shopcore:");
            assertThat(props.lock().prefix()).startsWith("shopcore:");
        });
    }

    @Test
    @DisplayName("교차 namespace 참조 0: notification prefix(notif:)가 이 설정에 없음")
    void noCrossNamespaceReference_notifPrefixAbsent() {
        contextRunner.run(context -> {
            RedisProperties props = context.getBean(RedisProperties.class);

            // 상대 프로젝트(notification) prefix가 이 설정에 존재하지 않아야 한다
            assertThat(props.auth().refreshPrefix()).doesNotContain("notif:");
            assertThat(props.auth().blacklistPrefix()).doesNotContain("notif:");
            assertThat(props.lock().prefix()).doesNotContain("notif:");
        });
    }

    /**
     * ApplicationContextRunner에서 RedisProperties를 활성화하기 위한 내부 설정 클래스.
     * Redis 자동설정은 포함하지 않아 브로커 연결 시도 없음.
     */
    @EnableConfigurationProperties(RedisProperties.class)
    static class RedisPropertiesTestConfig {
    }
}
