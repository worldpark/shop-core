package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 허용되지 않는 이미지 파일 업로드 시 발생하는 예외 (400 Bad Request).
 *
 * <p>비이미지 MIME 타입 또는 확장자 화이트리스트(jpg/jpeg/png/gif/webp) 위반 시 발생한다.
 */
public class InvalidImageFileException extends BusinessException {

    public InvalidImageFileException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
