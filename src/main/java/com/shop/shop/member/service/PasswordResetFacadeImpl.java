package com.shop.shop.member.service;

import com.shop.shop.member.spi.PasswordResetFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link PasswordResetFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다. package-private.
 * web은 인터페이스({@link PasswordResetFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임: PasswordResetService에만 위임(requestReset/isTokenValid/confirmReset).
 * 이 클래스는 레이어 경계 어댑터 역할만 수행하며 비즈니스 로직을 포함하지 않는다.
 */
@Service
@RequiredArgsConstructor
class PasswordResetFacadeImpl implements PasswordResetFacade {

    private final PasswordResetService passwordResetService;

    /** {@inheritDoc} */
    @Override
    public void requestReset(String email) {
        passwordResetService.requestReset(email);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isTokenValid(String token) {
        return passwordResetService.isTokenValid(token);
    }

    /** {@inheritDoc} */
    @Override
    public void confirmReset(String token, String newPassword) {
        passwordResetService.confirmReset(token, newPassword);
    }
}
