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
 *   <li>{@code type} — 저장소 구현 토글. {@code local}(기본) | {@code r2}. 환경변수 {@code SHOP_STORAGE_TYPE}로 전환.</li>
 *   <li>{@code root} — 로컬 파일 저장 root 디렉터리 (type=local 전용)</li>
 *   <li>{@code asset-base-url} — 공개 URL base (예: http://localhost:8080 또는 https://pub-*.r2.dev)</li>
 *   <li>{@code public-prefix} — 정적 자산 공개 URL prefix (예: /assets, R2에서는 빈 문자열)</li>
 *   <li>{@code allowed-extensions} — 허용 확장자 화이트리스트 (소문자, 예: jpg, jpeg, png, gif, webp)</li>
 *   <li>{@code max-images-per-product} — 상품당 이미지 최대 개수 (기본 10)</li>
 *   <li>{@code r2} — Cloudflare R2(S3 호환) 연결 설정 (type=r2 전용).
 *       access-key/secret-key는 반드시 환경변수로만 주입. 코드·yml·로그에 평문 노출 금지.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shop.storage")
@Getter
@Setter
public class StorageProperties {

    /**
     * 저장소 구현 토글. {@code local}(기본, 로컬 파일 시스템) | {@code r2}(Cloudflare R2 S3 호환).
     * 환경변수 {@code SHOP_STORAGE_TYPE}로 전환한다.
     */
    private String type = "local";

    /** 로컬 파일 저장 root 디렉터리 경로 (type=local 전용). */
    private String root;

    /** 공개 URL base (예: http://localhost:8080). DB 저장 금지 — URL 합성에만 사용. */
    private String assetBaseUrl;

    /** 정적 자산 공개 URL prefix (예: /assets). SecurityConfig permitAll 경로와 일치해야 한다. R2에서는 빈 문자열. */
    private String publicPrefix;

    /** 허용 확장자 화이트리스트 (소문자). 예: [jpg, jpeg, png, gif, webp]. */
    private List<String> allowedExtensions;

    /** 상품당 이미지 최대 개수. 기본값 10. */
    private int maxImagesPerProduct = 10;

    /**
     * Cloudflare R2(S3 호환) 연결 설정. type=r2일 때만 사용.
     * access-key/secret-key는 환경변수 전용 — 코드·yml·로그에 평문 노출 금지.
     */
    private R2 r2 = new R2();

    @Getter
    @Setter
    public static class R2 {

        /** R2 S3 호환 엔드포인트. 예: https://{accountId}.r2.cloudflarestorage.com */
        private String endpoint;

        /** R2 버킷 이름. */
        private String bucket;

        /**
         * R2 리전. Cloudflare R2는 {@code auto}를 사용한다.
         * S3Client Region.of("auto")로 매핑된다.
         */
        private String region = "auto";

        /**
         * R2 API 토큰 Access Key ID.
         * 반드시 환경변수 {@code SHOP_STORAGE_R2_ACCESS_KEY}로 주입. 코드·yml에 평문 금지.
         */
        private String accessKey;

        /**
         * R2 API 토큰 Secret Access Key.
         * 반드시 환경변수 {@code SHOP_STORAGE_R2_SECRET_KEY}로 주입. 코드·yml에 평문 금지.
         */
        private String secretKey;
    }
}
