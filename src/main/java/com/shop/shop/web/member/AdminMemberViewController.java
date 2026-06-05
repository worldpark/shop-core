package com.shop.shop.web.member;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.MemberSearchCondition;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.spi.AdminMemberFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
 * <p>레이어: AdminMemberViewController(@Controller) → {@link AdminMemberFacade}(published port)
 * → MemberService → MemberRepository.
 * facade가 email→userId 해석과 String→Role 변환을 내부에서 처리한다.
 * (ServiceResponse 미사용 — architecture-rule: View ViewController → Service 직접)
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code members} — {@code Page<MemberSummaryResponse>} (DTO, Entity 금지)</li>
 *   <li>{@code searchCondition} — {@link MemberSearchCondition} (@ModelAttribute 자동 추가)</li>
 * </ul>
 * View name: {@code admin/members}, redirect: {@code redirect:/admin/members}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 *
 * <p>원래 {@code member.controller.AdminMemberViewController}에서 {@code web.member}로 이동.
 * {@code MemberService}·{@code Role} 직접 의존 제거 → {@link AdminMemberFacade} 사용.
 */
@Slf4j
@Controller
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMemberViewController {

    private final AdminMemberFacade adminMemberFacade;

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

        Page<MemberSummaryResponse> members = adminMemberFacade
                .searchMembers(cond.getKeyword(), cond.getRole(), cond.getPage(), cond.getSize());

        model.addAttribute("members", members);
        // searchCondition은 @ModelAttribute로 자동 추가됨

        return "admin/members";
    }

    /**
     * 관리자 회원 권한 변경 폼 제출.
     * POST /admin/members/{memberId}/role
     *
     * <p>email→userId 해석과 role(String)→Role 변환은 facade 구현 내부에서 수행된다.
     * 컨트롤러는 {@code auth.getName()}(= email)과 role(String)을 그대로 facade에 전달한다.
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/admin/members} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/admin/members}.
     * View는 절대 JSON을 반환하지 않는다 (error-response-rule, Constraint).
     *
     * @param memberId 변경 대상 회원 ID
     * @param role     변경할 권한 문자열 (@RequestParam "role")
     * @param auth     SecurityContext 인증 객체 (username = email, form login session)
     * @param ra       RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/members
     */
    @PostMapping("/{memberId}/role")
    public String changeRole(
            @PathVariable long memberId,
            @RequestParam("role") String role,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            adminMemberFacade.changeRole(auth.getName(), memberId, role);
            ra.addFlashAttribute("flashSuccess", "권한이 변경되었습니다.");
        } catch (BusinessException e) {
            log.warn("권한 변경 실패: adminEmail={}, targetId={}, reason={}", auth.getName(), memberId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/members";
    }
}
