package com.shop.shop.member.controller;

import com.shop.shop.member.dto.LoginRequest;
import com.shop.shop.member.dto.MeResponse;
import com.shop.shop.member.dto.RefreshRequest;
import com.shop.shop.member.dto.TokenResponse;
import com.shop.shop.member.service.AuthServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 REST API 진입점.
 * 비즈니스 로직 없음 — AuthServiceResponse에 전적으로 위임한다 (Constraint).
 *
 * <p>레이어: RestController → AuthServiceResponse(ServiceResponse) → MemberService → MemberRepository
 *
 * <p>공개 엔드포인트: POST /login, POST /refresh (SecurityConfig permitAll)
 * <p>인증 필요: POST /logout, GET /me (SecurityConfig authenticated)
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthRestController {

    private final AuthServiceResponse authServiceResponse;

    /**
     * 로그인 — access/refresh token 발급.
     * POST /api/v1/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authServiceResponse.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Access token 재발급.
     * POST /api/v1/auth/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse response = authServiceResponse.refresh(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 로그아웃 — refresh 삭제 + access blacklist 등록.
     * POST /api/v1/auth/logout
     * Authorization: Bearer {accessToken} 헤더 필수.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authServiceResponse.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    /**
     * 내 정보 조회.
     * GET /api/v1/auth/me
     * 인증 필요 — JwtAuthenticationFilter가 SecurityContext를 설정한 후 호출.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        MeResponse response = authServiceResponse.me(authentication);
        return ResponseEntity.ok(response);
    }
}
