package com.shop.shop.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2ObjectStorage MinIO Testcontainers 통합 테스트.
 *
 * <p>MinIO는 S3 호환 컨테이너로 Cloudflare R2 라운드트립을 실 네트워크·과금 없이 검증한다.
 * Docker가 가용해야 실행된다.
 *
 * <p>검증:
 * <ul>
 *   <li>put → 반환 storageKey로 getObject 내용 일치</li>
 *   <li>delete → 재조회 404/NoSuchKeyException</li>
 *   <li>부재 키 delete가 예외 없이 통과 (멱등)</li>
 * </ul>
 */
@Testcontainers
class R2ObjectStorageMinioIntegrationTest {

    private static final String BUCKET = "shop-assets";
    private static final String MINIO_USERNAME = "minioadmin";
    private static final String MINIO_PASSWORD = "minioadmin";

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

        // 버킷 생성 — 이미 존재하면 무시 (컨테이너 공유로 @BeforeEach가 여러 번 호출될 때 발생)
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
            // 이미 존재하는 버킷 — 정상 상태, 무시
        }

        StorageProperties props = new StorageProperties();
        props.setRoot("./uploads");
        props.setAssetBaseUrl(minio.getS3URL());
        props.setPublicPrefix("");
        props.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));

        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setEndpoint(minio.getS3URL());
        r2.setBucket(BUCKET);
        r2.setRegion("us-east-1");
        r2.setAccessKey(MINIO_USERNAME);
        r2.setSecretKey(MINIO_PASSWORD);
        props.setR2(r2);

        r2ObjectStorage = new R2ObjectStorage(s3Client, props);
    }

    @Test
    @DisplayName("put → getObject — 업로드한 파일 내용을 키로 정확히 조회할 수 있음")
    void put_thenGetObject_contentMatches() throws Exception {
        byte[] content = "integration-test-image-content".getBytes();

        String storageKey = r2ObjectStorage.put(
                "products/10", "photo.jpg", "image/jpeg",
                new ByteArrayInputStream(content));

        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                GetObjectRequest.builder().bucket(BUCKET).key(storageKey).build());

        byte[] retrieved = response.readAllBytes();
        assertThat(retrieved).isEqualTo(content);
    }

    @Test
    @DisplayName("put → delete → 재조회 — 삭제 후 NoSuchKeyException 발생 (파일 부재 확인)")
    void put_delete_thenGetObject_notFound() {
        byte[] content = "delete-me".getBytes();

        String storageKey = r2ObjectStorage.put(
                "products/10", "to-delete.jpg", "image/jpeg",
                new ByteArrayInputStream(content));

        r2ObjectStorage.delete(storageKey);

        assertThatThrownBy(() ->
                s3Client.getObject(GetObjectRequest.builder().bucket(BUCKET).key(storageKey).build()))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    @DisplayName("delete — 존재하지 않는 키 삭제 시 예외 없음 (S3 멱등 계약 확인)")
    void delete_nonExistentKey_noException() {
        assertThatCode(() ->
                r2ObjectStorage.delete("products/10/nonexistent-" + System.currentTimeMillis() + ".jpg"))
                .doesNotThrowAnyException();
    }
}
