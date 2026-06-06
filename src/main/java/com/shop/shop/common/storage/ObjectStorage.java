package com.shop.shop.common.storage;

import java.io.InputStream;

/**
 * 객체(파일) 저장소 포트 인터페이스.
 *
 * <p>도메인 서비스는 이 인터페이스에만 의존한다.
 * 현재 구현체: {@link LocalObjectStorage} (로컬 파일 시스템).
 * 추후 {@code R2ObjectStorage}로 무중단 교체 가능 (static-asset-rule).
 *
 * <p>DB에는 반환된 storageKey만 저장한다 (host·절대경로 금지).
 */
public interface ObjectStorage {

    /**
     * 파일을 저장하고 storageKey를 반환한다.
     *
     * <p>동일 key 덮어쓰기 금지. key는 {@code {keyPrefix}/{uuid}.{ext}} 형태로 생성된다.
     *
     * @param keyPrefix      저장 경로 prefix (예: products/10)
     * @param originalFilename 원본 파일명 (확장자 추출용)
     * @param contentType    MIME 타입 (예: image/jpeg)
     * @param inputStream    파일 데이터 스트림
     * @return 저장된 파일의 storageKey (예: products/10/uuid.jpg)
     * @throws StorageException 저장 실패 시
     */
    String put(String keyPrefix, String originalFilename, String contentType, InputStream inputStream);

    /**
     * storageKey에 해당하는 파일을 삭제한다.
     *
     * <p>파일이 존재하지 않아도 예외를 던지지 않는다 (멱등성 보장).
     *
     * @param storageKey 삭제할 파일의 storageKey
     */
    void delete(String storageKey);
}
