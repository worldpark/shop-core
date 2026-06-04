package com.shop.shop.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * REST API 권한 부족 핸들러.
 * 인증됐으나 권한 부족(하위 권한 → 상위 리소스 접근) → 403 Forbidden + ErrorResponse JSON.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final AuthErrorResponseWriter errorResponseWriter;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        errorResponseWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                "접근 권한이 없습니다.",
                request.getRequestURI()
        );
    }
}
