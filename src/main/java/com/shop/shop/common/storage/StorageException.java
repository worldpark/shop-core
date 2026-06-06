package com.shop.shop.common.storage;

/**
 * 파일 저장소 I/O 실패 시 발생하는 런타임 예외.
 *
 * <p>checked IOException을 unchecked로 래핑해 서비스 레이어로 전파한다.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
