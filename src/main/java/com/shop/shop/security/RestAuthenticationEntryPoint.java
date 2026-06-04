package com.shop.shop.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * REST API 미인증 요청 진입점 핸들러.
 * Authorization 헤더 없음 / 토큰 만료·위조·blacklist → 401 Unauthorized + ErrorResponse JSON.
 * View 체인의 302 redirect와 분리된다 (REST 체인 전용).
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        errorResponseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "인증이 필요합니다.",
                request.getRequestURI()
        );
    }
}
