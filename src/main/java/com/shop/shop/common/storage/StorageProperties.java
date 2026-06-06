package com.shop.shop.common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 정적 자산 저장 설정 프로퍼티.
 *
 * <p>prefix: {@code shop.storage}
 * <ul>
 *   <li>{@code root} — 로컬 파일 저장 root 디렉터리</li>
 *   <li>{@code asset-base-url} — 공개 URL base (예: http://localhost:8080)</li>
 *   <li>{@code public-prefix} — 정적 자산 공개 URL prefix (예: /assets)</li>
 *   <li>{@code allowed-extensions} — 허용 확장자 화이트리스트 (소문자, 예: jpg, jpeg, png, gif, webp)</li>
 *   <li>{@code max-images-per-product} — 상품당 이미지 최대 개수 (기본 10)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shop.storage")
@Getter
@Setter
public class StorageProperties {

    /** 로컬 파일 저장 root 디렉터리 경로. */
    private String root;

    /** 공개 URL base (예: http://localhost:8080). DB 저장 금지 — URL 합성에만 사용. */
    private String assetBaseUrl;

    /** 정적 자산 공개 URL prefix (예: /assets). SecurityConfig permitAll 경로와 일치해야 한다. */
    private String publicPrefix;

    /** 허용 확장자 화이트리스트 (소문자). 예: [jpg, jpeg, png, gif, webp]. */
    private List<String> allowedExtensions;

    /** 상품당 이미지 최대 개수. 기본값 10. */
    private int maxImagesPerProduct = 10;
}
