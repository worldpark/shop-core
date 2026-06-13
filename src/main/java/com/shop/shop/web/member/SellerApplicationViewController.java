package com.shop.shop.web.member;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.SellerApplicationEligibility;
import com.shop.shop.member.dto.SellerApplicationResponse;
import com.shop.shop.member.spi.SellerApplicationFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/**
 * 판매자 신청 View 진입점 (신청자 전용).
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /seller-applications/**} → {@code authenticated}.
 * 비인증 → /login redirect (View 체인 기본 동작).
 * 신청 화면을 {@code hasRole("CONSUMER")}로 묶지 않는다 — SELLER/ADMIN도 진입 가능(안내 화면 도달 보장 §1.1).
 *
 * <p>레이어: SellerApplicationViewController(@Controller) → {@link SellerApplicationFacade}(published port)
 * → SellerApplicationService → SellerApplicationRepository.
 * facade가 email→userId 해석과 Role 비노출(scalar/DTO)을 내부에서 처리한다.
 * (ServiceResponse 미사용 — architecture-rule: View ViewController → spi facade)
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code eligible} — {@code boolean} (자격 여부: 폼 vs 안내 분기용)</li>
 *   <li>{@code reason} — {@code String} (자격 미달 사유, null=자격 있음)</li>
 *   <li>{@code form} — {@link SellerApplicationForm} (@ModelAttribute)</li>
 *   <li>{@code sellerApplication} — {@link SellerApplicationResponse} (내 신청, null=이력 없음).
 *       'application'은 Thymeleaf 암묵 scope 객체와 충돌하므로 사용 금지.</li>
 * </ul>
 * View name: {@code seller-applications/apply}, {@code seller-applications/me}
 * redirect: {@code redirect:/seller-applications/me}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 */
@Slf4j
@Controller
@RequestMapping("/seller-applications")
@RequiredArgsConstructor
public class SellerApplicationViewController {

    private final SellerApplicationFacade sellerApplicationFacade;

    /**
     * 판매자 신청 폼 화면.
     * GET /seller-applications/apply
     *
     * <p>facade가 현재 role/PENDING 여부를 {@link SellerApplicationEligibility}로 반환한다.
     * {@code eligible=true}이면 신청 폼, {@code eligible=false}이면 사유 안내를 렌더한다.
     * 이는 보안 차단이 아닌 UI 분기다 — SELLER/ADMIN도 진입 가능.
     *
     * @param auth  인증 객체 (username = email, View form login session)
     * @param model Spring MVC 모델
     * @return view name "seller-applications/apply"
     */
    @GetMapping("/apply")
    public String applyForm(Authentication auth, Model model) {
        SellerApplicationEligibility eligibility = sellerApplicationFacade.checkEligibility(auth.getName());
        model.addAttribute("eligible", eligibility.eligible());
        model.addAttribute("reason", eligibility.reason());
        model.addAttribute("form", new SellerApplicationForm());
        return "seller-applications/apply";
    }

    /**
     * 판매자 신청 폼 제출.
     * POST /seller-applications
     *
     * <p>@Valid 검증 실패 시 apply 화면 재렌더. 서비스 예외(자격/중복 409) 발생 시 flashError.
     * 성공 시 flashSuccess → redirect:/seller-applications/me (PRG 패턴).
     *
     * @param form  신청 폼 (@ModelAttribute "form", @Valid)
     * @param br    BindingResult (검증 실패 시 재렌더용)
     * @param auth  인증 객체 (username = email)
     * @param ra    RedirectAttributes (flash 속성 전달)
     * @return redirect:/seller-applications/me 또는 apply 재렌더
     */
    @PostMapping
    public String apply(
            @Valid @ModelAttribute("form") SellerApplicationForm form,
            BindingResult br,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        if (br.hasErrors()) {
            // 검증 실패: apply 화면 재렌더 (자격 정보 다시 추가)
            SellerApplicationEligibility eligibility = sellerApplicationFacade.checkEligibility(auth.getName());
            model.addAttribute("eligible", eligibility.eligible());
            model.addAttribute("reason", eligibility.reason());
            return "seller-applications/apply";
        }

        try {
            sellerApplicationFacade.apply(auth.getName(), form.toRequest());
            ra.addFlashAttribute("flashSuccess", "신청이 접수되었습니다.");
        } catch (BusinessException e) {
            log.warn("판매자 신청 실패: email={}, reason={}", auth.getName(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller-applications/me";
    }

    /**
     * 내 신청 상태 화면.
     * GET /seller-applications/me
     *
     * <p>신청 이력이 없으면 {@code application=null}로 안내 화면 렌더 (§1.7 View=빈 안내 200).
     * REST GET /me와 달리 404를 throw하지 않는다.
     * 승격된 SELLER도 본인 APPROVED 이력 조회 가능 (role 자격 제한 없음).
     *
     * @param auth  인증 객체 (username = email)
     * @param model Spring MVC 모델
     * @return view name "seller-applications/me"
     */
    @GetMapping("/me")
    public String me(Authentication auth, Model model) {
        Optional<SellerApplicationResponse> application =
                sellerApplicationFacade.findMyApplication(auth.getName());
        // 모델 키는 'sellerApplication' — 'application'은 Thymeleaf 암묵 객체(서블릿 컨텍스트 scope)와
        // 충돌해 모델 속성이 가려지므로 사용 금지(E2E로 발견).
        model.addAttribute("sellerApplication", application.orElse(null));
        return "seller-applications/me";
    }
}
