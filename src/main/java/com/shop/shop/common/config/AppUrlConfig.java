package com.shop.shop.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AppUrlProperties 활성화 설정.
 *
 * <p>StorageConfig / RedisConfig 선례와 동형.
 * {@code @EnableConfigurationProperties(AppUrlProperties.class)} 로 바인딩을 활성화한다.
 */
@Configuration
@EnableConfigurationProperties(AppUrlProperties.class)
public class AppUrlConfig {
}
