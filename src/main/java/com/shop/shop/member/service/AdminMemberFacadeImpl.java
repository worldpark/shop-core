package com.shop.shop.member.service;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.spi.AdminMemberFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@link AdminMemberFacade} 구현체.
 *
 * <p>member 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminMemberFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>role(String) → {@link Role} 변환 (null/빈 문자열 = null = 전체)</li>
 *   <li>adminEmail → adminUserId 해석 ({@link MemberService#getByEmail(String)})</li>
 *   <li>{@link com.shop.shop.member.domain.User} Entity → {@link MemberSummaryResponse} DTO 매핑</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class AdminMemberFacadeImpl implements AdminMemberFacade {

    private final MemberService memberService;

    /**
     * {@inheritDoc}
     *
     * <p>role(String) → {@link Role}로 변환 후 {@link MemberService#searchMembers}에 위임한다.
     * null/빈 문자열은 null(= 전체 조회)로 처리한다.
     * User Entity 페이지를 {@link MemberSummaryResponse#from(com.shop.shop.member.domain.User)} 매핑으로 변환한다.
     */
    @Override
    public Page<MemberSummaryResponse> searchMembers(String keyword, String role, int page, int size) {
        Role roleEnum = toRoleOrNull(role);
        return memberService.searchMembers(keyword, roleEnum, PageRequest.of(page, size))
                .map(MemberSummaryResponse::from);
    }

    /**
     * {@inheritDoc}
     *
     * <p>처리 순서:
     * <ol>
     *   <li>adminEmail → adminUserId: {@link MemberService#getByEmail(String)}.getId()</li>
     *   <li>role(String) → {@link Role}: {@code Role.valueOf(role)}</li>
     *   <li>{@link MemberService#changeRole(long, long, Role)} 위임</li>
     * </ol>
     * BusinessException은 변환 없이 그대로 전파한다.
     */
    @Override
    public void changeRole(String adminEmail, long targetMemberId, String role) {
        long adminUserId = memberService.getByEmail(adminEmail).getId();
        Role roleEnum = Role.valueOf(role);
        memberService.changeRole(adminUserId, targetMemberId, roleEnum);
    }

    /**
     * role 문자열을 {@link Role} enum으로 변환한다.
     * null 또는 빈 문자열이면 null을 반환한다 (= 전체 조회 필터).
     *
     * @param role 권한 문자열 (null/빈 문자열 허용)
     * @return Role enum 또는 null
     */
    private Role toRoleOrNull(String role) {
        if (!StringUtils.hasText(role)) {
            return null;
        }
        return Role.valueOf(role);
    }
}
