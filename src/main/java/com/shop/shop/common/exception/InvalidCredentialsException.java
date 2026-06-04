package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 로그인 자격증명 불일치 예외 (이메일 없음 / 비밀번호 불일치 통합).
 * 계정 열거 방지를 위해 두 경우 모두 동일 메시지를 반환한다.
 * HTTP 401 Unauthorized.
 */
public class InvalidCredentialsException extends BusinessException {

    private static final String DEFAULT_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    public InvalidCredentialsException() {
        super(DEFAULT_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    public InvalidCredentialsException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
