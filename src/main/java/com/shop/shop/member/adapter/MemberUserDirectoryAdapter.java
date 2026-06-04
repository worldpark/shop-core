package com.shop.shop.member.adapter;

import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.service.MemberService;
import com.shop.shop.product.spi.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * product.spi.UserDirectory 포트의 member 모듈 구현체 (어댑터 — 의존 역전).
 *
 * <p>member 모듈이 소유하는 어댑터다. product 모듈 소유 포트 {@link UserDirectory}를 구현한다.
 * 내부에서 {@link MemberService#getByEmail(String)}에 위임해 email → userId 변환을 수행한다.
 *
 * <p>의존 방향: member → product.spi(@NamedInterface) 단방향.
 * product는 member를 전혀 참조하지 않는다.
 *
 * <p>인증 세션의 email은 항상 존재하는 사용자라고 가정한다.
 * 미존재 시 {@link IllegalStateException}(시스템 불변식 위반)을 던진다.
 * 이는 클라이언트 입력 오류가 아니라 인증 세션과 회원 디렉터리의 불일치 — 운영 이상 상황이다.
 */
@Component
@RequiredArgsConstructor
public class MemberUserDirectoryAdapter implements UserDirectory {

    private final MemberService memberService;

    /**
     * 이메일로 userId 조회.
     *
     * @param email 인증 세션의 email (not null)
     * @return userId
     * @throws IllegalStateException email에 해당하는 사용자가 없음
     */
    @Override
    public long findUserIdByEmail(String email) {
        try {
            return memberService.getByEmail(email).getId();
        } catch (MemberNotFoundException e) {
            throw new IllegalStateException("인증 세션과 회원 디렉터리 불일치", e);
        }
    }
}
