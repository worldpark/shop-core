package com.shop.shop.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2ObjectStorage Cache-Control MinIO 통합 테스트 (plan §5.2).
 *
 * <p>MinIO S3 호환 컨테이너를 사용해 PutObject 시 지정한 Cache-Control 헤더가
 * 실제 객체 메타데이터에 저장되는지 검증한다.
 *
 * <p>044 MinIO 통합 패턴 재사용: @BeforeEach 직접 new S3Client 구성.
 * Docker가 가용해야 실행된다.
 */
@Testcontainers
class R2ObjectStorageCacheControlMinioIntegrationTest {

    private static final String BUCKET = "shop-assets-cache";
    private static final String MINIO_USERNAME = "minioadmin";
    private static final String MINIO_PASSWORD = "minioadmin";

    private static final String EXPECTED_CACHE_CONTROL = "public, max-age=31536000, immutable";

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
            .withUserName(MINIO_USERNAME)
            .withPassword(MINIO_PASSWORD);

    private S3Client s3Client;
    private R2ObjectStorage r2ObjectStorage;

    @BeforeEach
    void setUp() {
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO_USERNAME, MINIO_PASSWORD)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
            // 컨테이너 공유로 @BeforeEach가 여러 번 호출될 때 발생 — 정상 상태, 무시
        }

        StorageProperties props = new StorageProperties();
        props.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));

        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setEndpoint(minio.getS3URL());
        r2.setBucket(BUCKET);
        r2.setRegion("us-east-1");
        r2.setAccessKey(MINIO_USERNAME);
        r2.setSecretKey(MINIO_PASSWORD);
        r2.setCacheControl(EXPECTED_CACHE_CONTROL);
        props.setR2(r2);

        r2ObjectStorage = new R2ObjectStorage(s3Client, props);
    }

    @Test
    @DisplayName("put — PutObject 시 Cache-Control 헤더가 객체 메타데이터에 저장됨")
    void put_thenHeadObject_cacheControlMatches() {
        byte[] content = "cache-control-test-image".getBytes();

        String storageKey = r2ObjectStorage.put(
                "products/cache-test", "photo.jpg", "image/jpeg",
                new ByteArrayInputStream(content));

        HeadObjectResponse headResponse = s3Client.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(storageKey).build());

        assertThat(headResponse.cacheControl())
                .as("PutObject의 Cache-Control 헤더가 객체 메타에 저장되어야 함")
                .isEqualTo(EXPECTED_CACHE_CONTROL);
    }

    @Test
    @DisplayName("put — 커스텀 Cache-Control 값도 정확히 저장됨")
    void put_customCacheControl_storedCorrectly() {
        // 다른 cacheControl 값으로 별도 storage 인스턴스 구성
        String customCacheControl = "public, max-age=86400";

        StorageProperties propsCustom = new StorageProperties();
        propsCustom.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));

        StorageProperties.R2 r2Custom = new StorageProperties.R2();
        r2Custom.setEndpoint(minio.getS3URL());
        r2Custom.setBucket(BUCKET);
        r2Custom.setRegion("us-east-1");
        r2Custom.setAccessKey(MINIO_USERNAME);
        r2Custom.setSecretKey(MINIO_PASSWORD);
        r2Custom.setCacheControl(customCacheControl);
        propsCustom.setR2(r2Custom);

        R2ObjectStorage storageCustom = new R2ObjectStorage(s3Client, propsCustom);
        byte[] content = "custom-cache-test".getBytes();

        String storageKey = storageCustom.put(
                "products/custom-cache", "img.png", "image/png",
                new ByteArrayInputStream(content));

        HeadObjectResponse headResponse = s3Client.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(storageKey).build());

        assertThat(headResponse.cacheControl())
                .as("커스텀 Cache-Control 값이 객체 메타에 저장되어야 함")
                .isEqualTo(customCacheControl);
    }
}
