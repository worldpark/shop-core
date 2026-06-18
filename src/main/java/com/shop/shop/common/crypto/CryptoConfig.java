package com.shop.shop.common.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 암복호화 설정.
 *
 * <p>{@code shop.crypto.kek-file} 프로퍼티를 활성화한다. 값은 KEK 자체가 아니라
 * Base64 KEK가 저장된 파일 경로다.
 */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {
}
