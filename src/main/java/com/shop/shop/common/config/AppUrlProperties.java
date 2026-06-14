package com.shop.shop.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 URL 설정 프로퍼티.
 *
 * <p>prefix: {@code shop.app}
 * <ul>
 *   <li>{@code base-url} — 앱의 공개 기반 URL (예: http://localhost:8080). 하드코딩 금지.</li>
 * </ul>
 *
 * <p>용도: 비밀번호 재설정 링크(resetUrl) 조립 전용.
 * 정적 자산 URL은 {@code StorageProperties.assetBaseUrl}(shop.storage.asset-base-url)이 별도 관리한다.
 * 도메인 의미가 다르므로 재사용하지 않는다.
 *
 * <p>등록: {@link AppUrlConfig}의 {@code @EnableConfigurationProperties(AppUrlProperties.class)} 로 활성화.
 */
@ConfigurationProperties(prefix = "shop.app")
@Getter
@Setter
public class AppUrlProperties {

    /** 앱의 공개 기반 URL. 기본값: {@code http://localhost:8080}. */
    private String baseUrl = "http://localhost:8080";
}
