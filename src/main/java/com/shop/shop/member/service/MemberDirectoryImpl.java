package com.shop.shop.member.service;

import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.spi.MemberDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link MemberDirectory} 구현체 (package-private).
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * cart/order/payment는 인터페이스({@link MemberDirectory})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>MemberService 위임.
 * MemberNotFoundException → IllegalStateException 변환:
 * 인증 세션의 email/userId는 항상 존재해야 하는 사용자이므로,
 * 미존재 시 클라이언트 오류가 아니라 시스템 불변식 위반(운영 이상)으로 다룬다.
 * (MemberUserDirectoryAdapter 선례와 동일한 톤)
 */
@Service
@RequiredArgsConstructor
class MemberDirectoryImpl implements MemberDirectory {

    private final MemberService memberService;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException email에 해당하는 사용자 없음 (인증 세션과 회원 디렉터리 불일치)
     */
    @Override
    public long findUserIdByEmail(String email) {
        try {
            return memberService.getByEmail(email).getId();
        } catch (MemberNotFoundException e) {
            throw new IllegalStateException("인증 세션과 회원 디렉터리 불일치: email=" + email, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>MemberService.getById(userId) 위임 → User.email/name 스칼라 반환.
     * InvalidTokenException(미존재) → IllegalStateException 변환(시스템 불변식 위반).
     *
     * @throws IllegalStateException userId에 해당하는 사용자 없음
     */
    @Override
    public MemberContact findContactByUserId(long userId) {
        try {
            User user = memberService.getById(userId);
            return new MemberContact(user.getEmail(), user.getName());
        } catch (Exception e) {
            throw new IllegalStateException("회원 연락처 조회 실패 — userId=" + userId, e);
        }
    }
}
