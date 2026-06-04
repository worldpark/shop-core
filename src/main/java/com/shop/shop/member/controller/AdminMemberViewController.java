package com.shop.shop.member.controller;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.dto.MemberSearchCondition;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 회원 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>principal 통일: form login session principal = UserDetails(username = email).
 * {@code auth.getName()} → {@link MemberService#getByEmail(String)} → userId로 변환 후
 * {@link MemberService#changeRole(long, long, Role)}에 전달.
 * (REST 체인은 principal = userId long → AdminMemberServiceResponse에서 직접 추출 — §1.3)
 *
 * <p>레이어: AdminMemberViewController(@Controller) → MemberService → MemberRepository
 * (ServiceResponse 미사용 — architecture-rule: View ViewController → Service 직접)
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code members} — {@code Page<MemberSummaryResponse>} (DTO, Entity 금지)</li>
 *   <li>{@code searchCondition} — {@link MemberSearchCondition} (@ModelAttribute 자동 추가)</li>
 * </ul>
 * View name: {@code admin/members}, redirect: {@code redirect:/admin/members}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 */
@Slf4j
@Controller
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMemberViewController {

    private final MemberService memberService;

    /**
     * 관리자 회원 목록 화면.
     * GET /admin/members
     *
     * <p>검색 조건(@ModelAttribute "searchCondition")은 Thymeleaf 템플릿의 검색 폼 echo에 사용된다.
     * Entity를 모델에 직접 담지 않고 {@link MemberSummaryResponse} DTO로 변환한다 (Constraint).
     *
     * @param cond  검색 조건 (keyword / role / page / size, @ModelAttribute 자동 모델 바인딩)
     * @param model Spring MVC 모델
     * @return view name "admin/members"
     */
    @GetMapping
    public String list(
            @ModelAttribute("searchCondition") MemberSearchCondition cond,
            Model model) {

        Page<MemberSummaryResponse> members = memberService
                .searchMembers(cond.getKeyword(), cond.getRole(), PageRequest.of(cond.getPage(), cond.getSize()))
                .map(MemberSummaryResponse::from);

        model.addAttribute("members", members);
        // searchCondition은 @ModelAttribute로 자동 추가됨

        return "admin/members";
    }

    /**
     * 관리자 회원 권한 변경 폼 제출.
     * POST /admin/members/{memberId}/role
     *
     * <p>principal 통일: form login session에서 {@code auth.getName()}(= email)을 통해
     * {@link MemberService#getByEmail(String)}로 adminUserId를 얻은 후 {@code changeRole}에 전달.
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/admin/members} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/admin/members}.
     * View는 절대 JSON을 반환하지 않는다 (error-response-rule, Constraint).
     *
     * @param memberId 변경 대상 회원 ID
     * @param role     변경할 권한 (@RequestParam "role")
     * @param auth     SecurityContext 인증 객체 (username = email, form login session)
     * @param ra       RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/members
     */
    @PostMapping("/{memberId}/role")
    public String changeRole(
            @PathVariable long memberId,
            @RequestParam("role") Role role,
            Authentication auth,
            RedirectAttributes ra) {

        // View 체인 principal 통일: email → userId
        long adminUserId = memberService.getByEmail(auth.getName()).getId();

        try {
            memberService.changeRole(adminUserId, memberId, role);
            ra.addFlashAttribute("flashSuccess", "권한이 변경되었습니다.");
        } catch (BusinessException e) {
            log.warn("권한 변경 실패: adminUserId={}, targetId={}, reason={}", adminUserId, memberId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/members";
    }
}
