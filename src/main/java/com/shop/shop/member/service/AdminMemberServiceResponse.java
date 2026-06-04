package com.shop.shop.member.service;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.dto.RoleChangeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 관리자 회원 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View / Scheduler / EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — 하위 {@link MemberService}에 전적으로 위임.
 * Entity를 직접 반환하지 않고 DTO로 변환 후 반환한다 (Constraint).
 *
 * <p>레이어: AdminMemberRestController → AdminMemberServiceResponse → MemberService → MemberRepository
 */
@Service
@RequiredArgsConstructor
public class AdminMemberServiceResponse {

    private final MemberService memberService;

    /**
     * 회원 목록 검색 — REST 전용.
     *
     * <p>Page&lt;User&gt; → Page&lt;MemberSummaryResponse&gt; → PageResponse 변환.
     * 민감 정보(password_hash, token 등) 미포함 (Constraint — MemberSummaryResponse 보장).
     *
     * @param keyword 검색 키워드 (null/빈 문자열 = 전체)
     * @param role    권한 필터 (null = 전체)
     * @param page    페이지 번호 (0 기반)
     * @param size    페이지 크기
     * @return PageResponse&lt;MemberSummaryResponse&gt;
     */
    public PageResponse<MemberSummaryResponse> search(String keyword, Role role, int page, int size) {
        Page<MemberSummaryResponse> resultPage = memberService
                .searchMembers(keyword, role, PageRequest.of(page, size))
                .map(MemberSummaryResponse::from);

        return PageResponse.of(resultPage);
    }

    /**
     * 회원 권한 변경 — REST 전용.
     *
     * <p>JWT 기반 REST 요청의 principal = userId(long).
     * {@code (long) authentication.getPrincipal()}로 adminUserId 추출 (006 AuthServiceResponse 동일 규약).
     *
     * @param auth     SecurityContext의 인증 객체 (principal = userId long)
     * @param memberId 변경 대상 회원 ID
     * @param req      권한 변경 요청 DTO
     */
    public void changeRole(Authentication auth, long memberId, RoleChangeRequest req) {
        long adminUserId = (long) auth.getPrincipal();
        memberService.changeRole(adminUserId, memberId, req.role());
    }
}
