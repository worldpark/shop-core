package com.shop.shop.member.service;

import com.shop.shop.member.dto.PasswordResetConfirmRequest;
import com.shop.shop.member.dto.PasswordResetRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * REST 컨트롤러 전용 비밀번호 재설정 조합 서비스 (AccountServiceResponse 선례).
 *
 * <p>REST DTO → scalar/DTO 변환 후 {@link PasswordResetService}에 위임한다.
 * 비즈니스 로직 없음.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetServiceResponse {

    private final PasswordResetService passwordResetService;

    /**
     * 비밀번호 재설정 요청.
     * 항상 정상 반환 (이메일 존재 여부 무관 — enumeration 방지).
     *
     * @param req REST 요청 DTO
     */
    public void request(PasswordResetRequest req) {
        passwordResetService.requestReset(req.email());
    }

    /**
     * 비밀번호 재설정 확정.
     * 토큰 소비 후 비밀번호 교체. 토큰 무효 시 {@code InvalidPasswordResetTokenException}(400) 전파.
     *
     * @param req REST 요청 DTO
     */
    public void confirm(PasswordResetConfirmRequest req) {
        passwordResetService.confirmReset(req.token(), req.newPassword());
    }
}
