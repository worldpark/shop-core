package com.shop.shop.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2ObjectStorage 단위 테스트.
 *
 * <p>S3Client를 mock하여 PutObjectRequest/DeleteObjectRequest 구성 규칙을 검증한다.
 * 실 S3 연결 없이 빠르게 실행된다.
 */
@ExtendWith(MockitoExtension.class)
class R2ObjectStorageTest {

    @Mock
    private S3Client s3Client;

    private R2ObjectStorage r2ObjectStorage;

    private static final String BUCKET = "shop-assets";
    private static final String KEY_PREFIX = "products/10";

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setRoot("./uploads");
        props.setAssetBaseUrl("https://pub-x.r2.dev");
        props.setPublicPrefix("");
        props.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp"));

        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setEndpoint("https://test.r2.cloudflarestorage.com");
        r2.setBucket(BUCKET);
        r2.setRegion("auto");
        r2.setAccessKey("dummy-access-key");
        r2.setSecretKey("dummy-secret-key");
        props.setR2(r2);

        r2ObjectStorage = new R2ObjectStorage(s3Client, props);
    }

    @Test
    @DisplayName("put — PutObjectRequest의 bucket, contentType이 정확히 구성됨")
    void put_putObjectRequest_bucketAndContentTypeCorrect() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        r2ObjectStorage.put(KEY_PREFIX, "photo.jpg", "image/jpeg", sampleStream());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest captured = captor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("put — storageKey가 {prefix}/{uuid}.{ext} 패턴을 따름")
    void put_storageKey_followsKeyPattern() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String storageKey = r2ObjectStorage.put(KEY_PREFIX, "photo.jpg", "image/jpeg", sampleStream());

        Pattern expected = Pattern.compile(
                "^products/10/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");
        assertThat(storageKey).matches(expected);
    }

    @Test
    @DisplayName("put — PutObjectRequest의 key가 반환된 storageKey와 일치함")
    void put_requestKeyMatchesReturnedStorageKey() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String storageKey = r2ObjectStorage.put(KEY_PREFIX, "photo.jpg", "image/jpeg", sampleStream());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        assertThat(captor.getValue().key()).isEqualTo(storageKey);
    }

    @Test
    @DisplayName("put — S3Client 예외 발생 시 StorageException으로 변환됨")
    void put_s3Exception_convertedToStorageException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("업로드 실패").statusCode(500).build());

        assertThatThrownBy(() ->
                r2ObjectStorage.put(KEY_PREFIX, "photo.jpg", "image/jpeg", sampleStream()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("R2 업로드 실패");
    }

    @Test
    @DisplayName("delete — DeleteObjectRequest가 1회 위임됨")
    void delete_delegatesToS3Client() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        r2ObjectStorage.delete("products/10/uuid.jpg");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(1)).deleteObject(captor.capture());

        DeleteObjectRequest captured = captor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo("products/10/uuid.jpg");
    }

    @Test
    @DisplayName("delete — S3Client가 예외를 던지지 않으면 정상 반환 (멱등 동작 위임)")
    void delete_noS3Exception_returnsNormally() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        // 예외 없이 정상 반환
        r2ObjectStorage.delete("products/10/nonexistent.jpg");

        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("delete — S3Client 예외 발생 시 StorageException으로 변환됨")
    void delete_s3Exception_convertedToStorageException() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("삭제 실패").statusCode(500).build());

        assertThatThrownBy(() -> r2ObjectStorage.delete("products/10/uuid.jpg"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("R2 삭제 실패");
    }

    private InputStream sampleStream() {
        return new ByteArrayInputStream("fake-image-content".getBytes());
    }
}
