package com.shop.shop.member.controller;

import com.shop.shop.member.dto.PasswordResetConfirmRequest;
import com.shop.shop.member.dto.PasswordResetRequest;
import com.shop.shop.member.service.PasswordResetServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 비밀번호 재설정 REST API 컨트롤러.
 *
 * <p>비즈니스 로직 없음 — PasswordResetServiceResponse에만 위임.
 *
 * <p>POST /api/v1/auth/password-reset/request:
 * - 항상 200 + 동일 본문 (이메일 존재 여부 무관 — enumeration 방지)
 * - 비인증 접근 허용 (SecurityConfig permitAll)
 *
 * <p>POST /api/v1/auth/password-reset/confirm:
 * - 성공 200, 무효/만료/사용 토큰 → InvalidPasswordResetTokenException(400)
 * - 비밀번호 정책/일치 위반 → @Valid 400
 */
@Tag(name = "auth", description = "인증 — 로그인·토큰 재발급·로그아웃·비밀번호 재설정")
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetRestController {

    private static final String REQUEST_SUCCESS_MESSAGE =
            "비밀번호 재설정 메일을 보냈습니다(계정이 존재하는 경우).";

    private final PasswordResetServiceResponse passwordResetServiceResponse;

    /**
     * 비밀번호 재설정 요청.
     * 이메일 유효성 검증(@Valid) 후 서비스 위임.
     * 항상 200 + 동일 본문 반환 (enumeration 방지).
     */
    @Operation(summary = "비밀번호 재설정 이메일 요청")
    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> request(
            @Valid @RequestBody PasswordResetRequest req) {
        passwordResetServiceResponse.request(req);
        return ResponseEntity.ok(Map.of("message", REQUEST_SUCCESS_MESSAGE));
    }

    /**
     * 비밀번호 재설정 확정.
     * 검증(@Valid: @PasswordMatches/@Size) 통과 후 서비스 위임.
     * 성공 200, 무효 토큰 → InvalidPasswordResetTokenException → RestExceptionHandler 400.
     */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(
            @Valid @RequestBody PasswordResetConfirmRequest req) {
        passwordResetServiceResponse.confirm(req);
        return ResponseEntity.ok().build();
    }
}
