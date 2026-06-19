package com.shop.shop.member.service;

import com.shop.shop.member.spi.AdminBootstrapFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link AdminBootstrapFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminBootstrapFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>{@link MemberService}에 단순 위임하는 얇은 facade.
 * 예외는 변환 없이 그대로 전파한다 (common — OPEN 모듈이므로 web에서 직접 참조 가능).
 */
@Service
@RequiredArgsConstructor
class AdminBootstrapFacadeImpl implements AdminBootstrapFacade {

    private final MemberService memberService;

    /**
     * {@inheritDoc}
     *
     * <p>{@link MemberService#adminExists()}에 위임한다.
     */
    @Override
    public boolean adminExists() {
        return memberService.adminExists();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link MemberService#bootstrapFirstAdmin(String, String, String)}에 위임한다.
     * 예외는 변환 없이 그대로 전파한다.
     */
    @Override
    public void createFirstAdmin(String email, String rawPassword, String name) {
        memberService.bootstrapFirstAdmin(email, rawPassword, name);
    }
}
