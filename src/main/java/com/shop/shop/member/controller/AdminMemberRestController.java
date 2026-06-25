package com.shop.shop.member.controller;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.dto.RoleChangeRequest;
import com.shop.shop.member.service.AdminMemberServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 REST API 진입점.
 * 비즈니스 로직 없음 — {@link AdminMemberServiceResponse}에 전적으로 위임한다 (Constraint).
 *
 * <p>인가: SecurityConfig REST 체인에서 {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} 보장.
 * SELLER / CONSUMER → 403, 비인증 → 401 (EntryPoint / AccessDeniedHandler — 006 계승).
 *
 * <p>레이어: AdminMemberRestController → AdminMemberServiceResponse → MemberService → MemberRepository
 */
@Tag(name = "admin-member", description = "관리자 회원 관리 — 목록 조회·권한 변경 (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberRestController {

    private final AdminMemberServiceResponse adminMemberServiceResponse;

    /**
     * 회원 목록 조회/검색.
     * GET /api/v1/admin/members
     *
     * @param keyword 검색 키워드 (이메일 또는 이름, optional)
     * @param role    권한 필터 (optional)
     * @param page    페이지 번호 (기본값 0)
     * @param size    페이지 크기 (기본값 20)
     * @return 200 PageResponse&lt;MemberSummaryResponse&gt;
     */
    @Operation(summary = "회원 목록 조회/검색 (ADMIN)")
    @GetMapping
    public ResponseEntity<PageResponse<MemberSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<MemberSummaryResponse> response = adminMemberServiceResponse.search(keyword, role, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 회원 권한 변경.
     * PATCH /api/v1/admin/members/{memberId}/role
     *
     * @param memberId 변경 대상 회원 ID
     * @param req      권한 변경 요청 ({@code @NotNull role})
     * @param auth     SecurityContext 인증 객체 (principal = userId long)
     * @return 200 OK
     */
    @PatchMapping("/{memberId}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable long memberId,
            @Valid @RequestBody RoleChangeRequest req,
            Authentication auth) {

        adminMemberServiceResponse.changeRole(auth, memberId, req);
        return ResponseEntity.ok().build();
    }
}
