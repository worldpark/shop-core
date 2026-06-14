package com.shop.shop.common.exception;

/**
 * 비밀번호 재설정 토큰이 유효하지 않거나 만료되었을 때 발생하는 예외.
 *
 * <p>HTTP 400 ({@link org.springframework.http.HttpStatus#BAD_REQUEST}) — BusinessException 기본값.
 * 토큰 값을 메시지에 포함하지 않는다 (보안 — 토큰 노출 금지).
 *
 * <p>발생 조건:
 * <ul>
 *   <li>토큰이 존재하지 않음 (미발급 또는 만료)</li>
 *   <li>토큰이 이미 사용됨 (1회용 소비 후)</li>
 *   <li>토큰이 위조됨</li>
 * </ul>
 *
 * <p>{@code RestExceptionHandler}가 400 {@code ErrorResponse}로 변환한다.
 */
public class InvalidPasswordResetTokenException extends BusinessException {

    public InvalidPasswordResetTokenException() {
        super("유효하지 않거나 만료된 토큰입니다.");
    }
}
