package com.shop.shop.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 암복호화 설정 프로퍼티.
 *
 * <p>prefix: {@code shop.crypto}
 * <ul>
 *   <li>{@code kek-file} — Base64 KEK가 저장된 파일 경로</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shop.crypto")
public record CryptoProperties(
        Path kekFile
) {
}
