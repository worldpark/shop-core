package com.shop.shop.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Cloudflare R2(S3 호환 API) 기반 {@link ObjectStorage} 구현체.
 *
 * <p>{@code shop.storage.type=r2}일 때만 활성화된다.
 *
 * <p>키 규칙: {@code {keyPrefix}/{uuid}.{ext}} — {@link LocalObjectStorage}와 동일.
 * DB에 저장된 storageKey는 저장소 교체 후에도 그대로 유효하다.
 *
 * <p>버퍼링: S3 SDK는 InputStream 길이가 미지정이면 멀티파트로 전환해 복잡도가 증가한다.
 * 이미지 업로드는 {@code spring.servlet.multipart.max-file-size}(기본 10MB) 상한이 메모리를
 * 제한하므로 {@code in.readAllBytes()} + {@code RequestBody.fromBytes}로 단순 버퍼링한다.
 *
 * <p>delete 멱등성: S3 DeleteObject는 키가 없어도 성공(2xx)을 반환하므로 포트 계약과 일치한다.
 *
 * <p>로깅: storageKey만 기록하며 access-key/secret-key는 로그에 출력하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.storage", name = "type", havingValue = "r2")
@RequiredArgsConstructor
public class R2ObjectStorage implements ObjectStorage {

    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>originalFilename에서 확장자 추출</li>
     *   <li>storageKey = {keyPrefix}/{uuid}.{ext} 생성</li>
     *   <li>InputStream을 바이트 배열로 버퍼링 (이미지 상한 내)</li>
     *   <li>PutObjectRequest(bucket, key, contentType)로 R2에 업로드</li>
     * </ol>
     *
     * <p>LocalObjectStorage와 달리 동일 key 존재 여부를 사전 검사하지 않는다.
     * UUID 기반 key는 충돌 가능성이 사실상 없고, R2에서 HEAD 요청은 비용이 발생한다.
     */
    @Override
    public String put(String keyPrefix, String originalFilename, String contentType, InputStream inputStream) {
        String ext = extractExtension(originalFilename);
        String storageKey = keyPrefix + "/" + UUID.randomUUID() + "." + ext;
        String bucket = storageProperties.getR2().getBucket();

        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new StorageException("R2 업로드 실패 — InputStream 읽기 오류: " + storageKey, e);
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .contentType(contentType)
                    .cacheControl(storageProperties.getR2().getCacheControl())
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            // S3Exception(AwsServiceException 계열) — HTTP 4xx/5xx 응답 오류
            throw new StorageException("R2 업로드 실패: " + storageKey, e);
        } catch (SdkException e) {
            // SdkClientException 계열 — 타임아웃(ApiCallTimeoutException/ApiCallAttemptTimeoutException),
            // 연결 실패 등 클라이언트 사이드 예외. S3Exception의 형제(sibling)라 위 catch에 안 잡힌다.
            throw new StorageException("R2 업로드 실패: " + storageKey, e);
        }

        log.debug("R2 파일 업로드 완료: {}", storageKey);
        return storageKey;
    }

    /**
     * {@inheritDoc}
     *
     * <p>S3 DeleteObject는 키가 존재하지 않아도 성공(2xx)을 반환한다 — 멱등성 보장.
     */
    @Override
    public void delete(String storageKey) {
        String bucket = storageProperties.getR2().getBucket();
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build();
            s3Client.deleteObject(request);
            log.debug("R2 파일 삭제 완료 (또는 부재): {}", storageKey);
        } catch (S3Exception e) {
            // S3Exception(AwsServiceException 계열) — HTTP 4xx/5xx 응답 오류
            throw new StorageException("R2 삭제 실패: " + storageKey, e);
        } catch (SdkException e) {
            // SdkClientException 계열 — 타임아웃 등 클라이언트 사이드 예외
            throw new StorageException("R2 삭제 실패: " + storageKey, e);
        }
    }

    /**
     * 파일명에서 확장자를 추출한다.
     * 확장자가 없으면 빈 문자열을 반환한다.
     */
    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }
}
