package com.shop.shop.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * shop-core Redis 설정.
 *
 * 역할:
 * 1. RedisProperties 활성화 (@EnableConfigurationProperties)
 *    — key namespace(refreshPrefix, blacklistPrefix, lockPrefix)와 TTL 기본값을 바인딩한다.
 *
 * StringRedisTemplate은 Spring Boot RedisAutoConfiguration이 자동으로 제공한다.
 * key/value 모두 StringRedisSerializer로 직렬화되는 기본 설정으로 충분하므로 별도 빈 정의는 불필요.
 * 복잡 객체 캐싱이 필요해지면 RedisTemplate<String,Object>(JSON 직렬화) 빈을 별도 추가한다(YAGNI).
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {
}
