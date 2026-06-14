package com.shop.shop.member.service;

import com.shop.shop.member.dto.PasswordChangeRequest;
import com.shop.shop.member.dto.ProfileUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 계정 self-service REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — 하위 {@link AccountService}에 전적으로 위임.
 * principal(userId) 추출 후 AccountService에 위임한다.
 *
 * <p>레이어: MemberAccountRestController → AccountServiceResponse → AccountService → MemberRepository
 *
 * <p>JWT 기반 REST 요청의 principal = userId(long) (JwtAuthenticationFilter 규약).
 * {@code (long) authentication.getPrincipal()} — MemberServiceResponse.me 선례 동일.
 */
@Service
@RequiredArgsConstructor
public class AccountServiceResponse {

    private final AccountService accountService;

    /**
     * 비밀번호 변경 — REST 전용.
     *
     * <p>principal에서 userId 추출 후 AccountService.changePassword에 위임.
     * 반환값 없음(204 — 비번/해시 비노출).
     *
     * @param auth JWT 인증 객체 (principal = userId long)
     * @param req  비밀번호 변경 요청 DTO
     */
    public void changePassword(Authentication auth, PasswordChangeRequest req) {
        long userId = (long) auth.getPrincipal();
        accountService.changePassword(userId, req.currentPassword(), req.newPassword());
    }

    /**
     * 회원 정보 수정 — REST 전용.
     *
     * <p>principal에서 userId 추출 후 AccountService.updateProfile에 위임.
     * 반환값 없음(204).
     *
     * @param auth JWT 인증 객체 (principal = userId long)
     * @param req  정보 수정 요청 DTO
     */
    public void updateProfile(Authentication auth, ProfileUpdateRequest req) {
        long userId = (long) auth.getPrincipal();
        accountService.updateProfile(userId, req.name(), req.phone());
    }

    /**
     * 탈퇴 처리 — REST 전용.
     *
     * <p>principal에서 userId 추출 후 AccountService.withdraw에 위임.
     * 반환값 없음(204).
     *
     * @param auth JWT 인증 객체 (principal = userId long)
     */
    public void withdraw(Authentication auth) {
        long userId = (long) auth.getPrincipal();
        accountService.withdraw(userId);
    }
}
