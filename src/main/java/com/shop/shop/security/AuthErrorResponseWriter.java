package com.shop.shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 필터/엔트리포인트 레벨 인증·인가 실패 시 ErrorResponse JSON 직렬화 헬퍼.
 * RestExceptionHandler(@RestControllerAdvice)와 동일 포맷(ErrorResponse)을 보장한다.
 *
 * <p>사용처: RestAuthenticationEntryPoint(401), RestAccessDeniedHandler(403)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorResponseWriter {

    private final ObjectMapper objectMapper;

    /**
     * HTTP 응답에 ErrorResponse JSON을 쓴다.
     *
     * @param response HttpServletResponse
     * @param status   HTTP 상태 코드
     * @param message  오류 메시지 (클라이언트 노출 가능, 내부 정보 금지)
     * @param path     요청 경로
     */
    public void write(HttpServletResponse response, HttpStatus status, String message, String path) {
        try {
            ErrorResponse errorResponse = ErrorResponse.of(status, message, path);
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), errorResponse);
        } catch (IOException e) {
            log.error("ErrorResponse 직렬화 실패", e);
        }
    }
}
