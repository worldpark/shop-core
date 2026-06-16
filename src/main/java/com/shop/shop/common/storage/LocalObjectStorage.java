package com.shop.shop.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 로컬 파일 시스템 기반 {@link ObjectStorage} 구현체.
 *
 * <p>저장 경로: {@code root}/{keyPrefix}/{uuid}.{ext}
 * <p>path traversal 방지: 정규화된 경로가 root 하위인지 검증한다.
 * <p>동일 key 덮어쓰기 금지: 파일이 이미 존재하면 {@link StorageException}을 던진다.
 * <p>추후 R2ObjectStorage 교체 시 이 구현체를 교체하면 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.storage", name = "type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalObjectStorage implements ObjectStorage {

    private final StorageProperties storageProperties;

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>originalFilename에서 확장자 추출</li>
     *   <li>storageKey = {keyPrefix}/{uuid}.{ext} 생성</li>
     *   <li>path traversal 방지 검증</li>
     *   <li>동일 key 중복 체크</li>
     *   <li>디렉터리 생성 후 파일 저장 (COPY_ATTRIBUTES 비사용, CREATE_NEW 대체 방식)</li>
     * </ol>
     */
    @Override
    public String put(String keyPrefix, String originalFilename, String contentType, InputStream inputStream) {
        String ext = extractExtension(originalFilename);
        String storageKey = keyPrefix + "/" + UUID.randomUUID() + "." + ext;

        Path resolvedPath = resolveAndValidate(storageKey);

        if (Files.exists(resolvedPath)) {
            throw new StorageException("동일 storageKey 파일이 이미 존재합니다: " + storageKey);
        }

        try {
            Files.createDirectories(resolvedPath.getParent());
            Files.copy(inputStream, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("파일 저장에 실패했습니다: " + storageKey, e);
        }

        log.debug("파일 저장 완료: {}", storageKey);
        return storageKey;
    }

    /**
     * {@inheritDoc}
     *
     * <p>파일이 존재하지 않으면 아무 작업도 하지 않는다 (멱등성).
     */
    @Override
    public void delete(String storageKey) {
        Path resolvedPath = resolveAndValidate(storageKey);
        try {
            boolean deleted = Files.deleteIfExists(resolvedPath);
            if (deleted) {
                log.debug("파일 삭제 완료: {}", storageKey);
            } else {
                log.debug("삭제 대상 파일 없음 (이미 삭제됨): {}", storageKey);
            }
        } catch (IOException e) {
            throw new StorageException("파일 삭제에 실패했습니다: " + storageKey, e);
        }
    }

    /**
     * storageKey를 절대 경로로 변환하고 root 하위 경로인지 검증한다 (path traversal 방지).
     *
     * @param storageKey 검증할 storageKey
     * @return 정규화된 절대 Path
     * @throws StorageException root 외부 경로 접근 시도 시
     */
    private Path resolveAndValidate(String storageKey) {
        Path root = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
        Path resolved = root.resolve(storageKey).normalize();

        if (!resolved.startsWith(root)) {
            throw new StorageException("허용되지 않는 경로입니다: " + storageKey);
        }
        return resolved;
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
