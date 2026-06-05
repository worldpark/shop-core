package com.shop.shop.member.service;

import com.shop.shop.member.spi.MemberSignupFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link MemberSignupFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link MemberSignupFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>기존 {@link MemberService#signup} 메서드에 단순 위임한다.
 * DuplicateEmailException 전파 — 변환 없이 그대로 전파한다.
 */
@Service
@RequiredArgsConstructor
class MemberSignupFacadeImpl implements MemberSignupFacade {

    private final MemberService memberService;

    /**
     * {@inheritDoc}
     *
     * <p>{@link MemberService#signup}에 위임한다.
     * DuplicateEmailException은 변환 없이 그대로 전파한다.
     */
    @Override
    public void signup(String email, String password, String name, String phone) {
        memberService.signup(email, password, name, phone);
    }
}
