package com.shop.shop.common.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 정적 자산 저장소 설정.
 *
 * <p>역할:
 * <ol>
 *   <li>{@link StorageProperties} 활성화 — shop.storage.* 프로퍼티 바인딩</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
}
