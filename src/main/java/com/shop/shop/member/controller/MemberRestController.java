package com.shop.shop.member.controller;

import com.shop.shop.member.dto.MeResponse;
import com.shop.shop.member.dto.SignupRequest;
import com.shop.shop.member.dto.SignupResponse;
import com.shop.shop.member.service.MemberServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 REST API 진입점.
 * 비즈니스 로직 없음 — MemberServiceResponse에 전적으로 위임한다 (Constraint).
 *
 * <p>레이어: RestController → MemberServiceResponse(ServiceResponse) → MemberService → MemberRepository
 *
 * <p>공개 엔드포인트: POST /signup (SecurityConfig permitAll)
 * <p>인증 필요: GET /me (SecurityConfig anyRequest authenticated + JWT)
 */
@Tag(name = "member", description = "회원 — 회원가입·내 정보 조회")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberRestController {

    private final MemberServiceResponse memberServiceResponse;

    /**
     * 회원가입 — CONSUMER 계정 생성.
     * POST /api/v1/members/signup
     *
     * <p>공개 API. 성공 시 201 Created + SignupResponse (비번 미포함).
     * 검증 실패: 400 ErrorResponse JSON (RestExceptionHandler).
     * 중복 이메일: 409 ErrorResponse JSON.
     */
    @Operation(summary = "회원가입 — CONSUMER 계정 생성")
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = memberServiceResponse.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 정보 조회.
     * GET /api/v1/members/me
     *
     * <p>인증 필요 — JwtAuthenticationFilter가 SecurityContext를 설정한 후 호출.
     * principal = userId(long) (006 JwtAuthenticationFilter 규약).
     * 비인증 요청: 401 JSON (RestAuthenticationEntryPoint).
     */
    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        MeResponse response = memberServiceResponse.me(authentication);
        return ResponseEntity.ok(response);
    }
}
