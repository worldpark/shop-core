package com.shop.shop.member.controller;

import com.shop.shop.member.dto.PasswordChangeRequest;
import com.shop.shop.member.dto.ProfileUpdateRequest;
import com.shop.shop.member.service.AccountServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 self-service REST API 진입점.
 * 비즈니스 로직 없음 — AccountServiceResponse에 전적으로 위임한다 (Constraint).
 *
 * <p>레이어: RestController → AccountServiceResponse → AccountService → MemberRepository
 *
 * <p>모든 경로 authenticated — /me 셀프 경로로 IDOR 원천 차단.
 * GET /me는 기존 MemberRestController에 존재 — 여기서 중복 선언 금지.
 *
 * <p>권한: authenticated (CONSUMER/SELLER/ADMIN 모두 본인 계정 관리 가능).
 * SecurityConfig anyRequest().authenticated()가 커버.
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberAccountRestController {

    private final AccountServiceResponse accountServiceResponse;

    /**
     * 비밀번호 변경.
     * PATCH /api/v1/members/me/password
     *
     * <p>인증 필요 — JwtAuthenticationFilter가 SecurityContext를 설정한 후 호출.
     * 현재 비번 불일치 시 400 ErrorResponse JSON (RestExceptionHandler → BusinessException).
     * confirm 불일치 시 400 (@PasswordMatches 필드 위반).
     * 성공 시 204 No Content (비번/해시 응답 비노출).
     */
    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(Authentication authentication,
                                               @Valid @RequestBody PasswordChangeRequest request) {
        accountServiceResponse.changePassword(authentication, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * 회원 정보 수정 (name/phone).
     * PATCH /api/v1/members/me
     *
     * <p>인증 필요. email/role/password는 이 경로로 변경 불가.
     * 성공 시 204 No Content.
     */
    @PatchMapping("/me")
    public ResponseEntity<Void> updateProfile(Authentication authentication,
                                              @Valid @RequestBody ProfileUpdateRequest request) {
        accountServiceResponse.updateProfile(authentication, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * 탈퇴 처리 (소프트 삭제).
     * DELETE /api/v1/members/me
     *
     * <p>인증 필요. status=WITHDRAWN, deletedAt 설정 후 refresh 무효화.
     * 물리 삭제 없음. 성공 시 204 No Content.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(Authentication authentication) {
        accountServiceResponse.withdraw(authentication);
        return ResponseEntity.noContent().build();
    }
}
