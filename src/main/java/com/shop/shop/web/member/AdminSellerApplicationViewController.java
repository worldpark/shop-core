package com.shop.shop.web.member;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import com.shop.shop.member.spi.AdminSellerApplicationFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 판매자 신청 View 진입점 (admin 전용).
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} (008이 이미 존재).
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>레이어: AdminSellerApplicationViewController(@Controller) → {@link AdminSellerApplicationFacade}(published port)
 * → SellerApplicationService → SellerApplicationRepository.
 * facade가 email→userId 해석을 내부에서 처리한다.
 * (ServiceResponse 미사용 — architecture-rule: View ViewController → spi facade)
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code applications} — {@code Page<SellerApplicationSummaryResponse>} (DTO, Entity 금지)</li>
 *   <li>{@code status} — {@code String} (상태 필터 echo용, null=전체)</li>
 * </ul>
 * View name: {@code admin/seller-applications}
 * redirect: {@code redirect:/admin/seller-applications}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 * 반려 폼 필드: {@code rejectReason}
 */
@Slf4j
@Controller
@RequestMapping("/admin/seller-applications")
@RequiredArgsConstructor
public class AdminSellerApplicationViewController {

    private final AdminSellerApplicationFacade adminSellerApplicationFacade;

    /**
     * 판매자 신청 심사 목록 화면.
     * GET /admin/seller-applications
     *
     * <p>Entity를 모델에 직접 담지 않고 {@link SellerApplicationSummaryResponse} DTO 페이지를 사용한다.
     *
     * @param status status 필터 (null/빈 문자열 = 전체, PENDING/APPROVED/REJECTED)
     * @param page   페이지 번호 (기본값 0)
     * @param size   페이지 크기 (기본값 20)
     * @param model  Spring MVC 모델
     * @return view name "admin/seller-applications"
     */
    @GetMapping
    public String list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        Page<SellerApplicationSummaryResponse> applications =
                adminSellerApplicationFacade.search(status, page, size);

        model.addAttribute("applications", applications);
        // null이면 빈 문자열로 저장 (Thymeleaf EL에서 attributeExists를 통과시키기 위해)
        model.addAttribute("status", status != null ? status : "");

        return "admin/seller-applications";
    }

    /**
     * 판매자 신청 승인 폼 제출.
     * POST /admin/seller-applications/{id}/approve
     *
     * <p>성공 시: {@code flashSuccess} + redirect:/admin/seller-applications (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + redirect.
     * View는 절대 JSON을 반환하지 않는다 (error-response-rule, Constraint).
     *
     * @param id   신청 ID
     * @param auth 인증 객체 (username = email, View form login session)
     * @param ra   RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/seller-applications
     */
    @PostMapping("/{id}/approve")
    public String approve(
            @PathVariable long id,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            adminSellerApplicationFacade.approve(auth.getName(), id);
            ra.addFlashAttribute("flashSuccess", "승인되었습니다.");
        } catch (BusinessException e) {
            log.warn("판매자 신청 승인 실패: adminEmail={}, applicationId={}, reason={}",
                    auth.getName(), id, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/seller-applications";
    }

    /**
     * 판매자 신청 반려 폼 제출.
     * POST /admin/seller-applications/{id}/reject
     *
     * <p>폼 필드: {@code rejectReason} (Backend-View Contract).
     * 성공 시: flashSuccess + redirect. 실패 시: flashError + redirect.
     *
     * @param id           신청 ID
     * @param rejectReason 반려 사유 (@RequestParam "rejectReason")
     * @param auth         인증 객체 (username = email)
     * @param ra           RedirectAttributes
     * @return redirect:/admin/seller-applications
     */
    @PostMapping("/{id}/reject")
    public String reject(
            @PathVariable long id,
            @RequestParam("rejectReason") String rejectReason,
            Authentication auth,
            RedirectAttributes ra) {

        try {
            adminSellerApplicationFacade.reject(auth.getName(), id, rejectReason);
            ra.addFlashAttribute("flashSuccess", "반려되었습니다.");
        } catch (BusinessException e) {
            log.warn("판매자 신청 반려 실패: adminEmail={}, applicationId={}, reason={}",
                    auth.getName(), id, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/seller-applications";
    }
}
