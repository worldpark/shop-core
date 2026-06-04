package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * JWT 토큰 유효성 검사 실패 예외.
 * 만료 / 서명 위조 / 형식 오류 / refresh 무효 / blacklist 등록 시 사용.
 * HTTP 401 Unauthorized.
 */
public class InvalidTokenException extends BusinessException {

    private static final String DEFAULT_MESSAGE = "유효하지 않은 토큰입니다.";

    public InvalidTokenException() {
        super(DEFAULT_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    public InvalidTokenException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
