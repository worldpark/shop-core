package com.shop.shop.member.service;

import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.AccountInfo;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.spi.AccountFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AccountFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다. package-private.
 * web은 인터페이스({@link AccountFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>email → userId 해석 ({@code findActiveByEmail(email).getId()}) — 활성 조회로 탈퇴 계정 차단</li>
 *   <li>{@link User} Entity → {@link AccountInfo} DTO 변환</li>
 *   <li>AccountService에 위임</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class AccountFacadeImpl implements AccountFacade {

    private final MemberRepository memberRepository;
    private final AccountService accountService;

    /**
     * {@inheritDoc}
     *
     * <p>findActiveByEmail로 활성 회원만 조회 — 탈퇴 계정은 화면에 도달하지 않아야 함(경미2 정정).
     */
    @Override
    @Transactional(readOnly = true)
    public AccountInfo getAccountInfo(String email) {
        User user = findActiveUser(email);
        return new AccountInfo(user.getEmail(), user.getName(), user.getPhone());
    }

    /** {@inheritDoc} */
    @Override
    public void changePassword(String email, String currentPassword, String newPassword) {
        long userId = findActiveUser(email).getId();
        accountService.changePassword(userId, currentPassword, newPassword);
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(String email, String name, String phone) {
        long userId = findActiveUser(email).getId();
        accountService.updateProfile(userId, name, phone);
    }

    /** {@inheritDoc} */
    @Override
    public void withdraw(String email) {
        long userId = findActiveUser(email).getId();
        accountService.withdraw(userId);
    }

    /**
     * 활성 회원 조회 헬퍼.
     *
     * @param email 이메일
     * @return 활성 회원 User
     * @throws UsernameNotFoundException 활성 회원 없음 (탈퇴 계정 포함)
     */
    private User findActiveUser(String email) {
        return memberRepository.findActiveByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("활성 회원을 찾을 수 없습니다: " + email));
    }
}
