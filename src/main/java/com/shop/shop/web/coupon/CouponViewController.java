package com.shop.shop.web.coupon;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.spi.CouponFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
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

import java.util.List;

/**
 * 사용자 쿠폰함 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /coupons}, {@code /coupons/**}
 * → {@code hasRole("CONSUMER")} 보장. 비인증 → /login redirect.
 *
 * <p>레이어: CouponViewController(@Controller)
 * → {@link CouponFacade}(order.spi published port)
 * → CouponService → UserCouponRepository.
 * web은 order 내부(domain·service·repository) 직접 참조 금지.
 *
 * <p>모델 키 계약:
 * <ul>
 *   <li>{@code couponWallet} — {@code List<UserCouponResponse>} (보유 쿠폰 목록)</li>
 *   <li>{@code couponClaimForm} — {@link CouponClaimForm} (발급 폼 Backing Object)</li>
 * </ul>
 * View name: {@code coupon/wallet}, redirect: {@code redirect:/coupons}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 */
@Slf4j
@Controller
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponViewController {

    private final CouponFacade couponFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 쿠폰함 화면.
     * GET /coupons
     *
     * <p>principal email → CouponFacade.getMyWallet(email) → 미사용/사용/만료 구분 목록 표시.
     *
     * @param auth  SecurityContext 인증 객체 (JWT 쿠키 인증, email = principal name)
     * @param model Spring MVC 모델
     * @return view name "coupon/wallet"
     */
    @GetMapping
    public String wallet(Authentication auth, Model model) {
        CurrentActor actor = currentActorResolver.resolve(auth);
        List<UserCouponResponse> wallet = couponFacade.getMyWallet(actor.email());
        model.addAttribute("couponWallet", wallet);

        if (!model.containsAttribute("couponClaimForm")) {
            model.addAttribute("couponClaimForm", new CouponClaimForm());
        }

        return "coupon/wallet";
    }

    /**
     * 쿠폰 코드 발급 폼 제출.
     * POST /coupons/claim
     *
     * <p>검증 실패(@Valid): flashError + redirect:/coupons (폼 단순 — PRG).
     * 성공: CouponFacade.claim(email, code) → flashSuccess + redirect:/coupons.
     * BusinessException(404/400/409): flashError(메시지 그대로) + redirect:/coupons.
     *
     * @param form          발급 폼 (code 필드)
     * @param bindingResult 폼 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param ra            RedirectAttributes (flash 속성 전달)
     * @return redirect:/coupons
     */
    @PostMapping("/claim")
    public String claim(
            @Valid @ModelAttribute("couponClaimForm") CouponClaimForm form,
            BindingResult bindingResult,
            Authentication auth,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getAllErrors().get(0).getDefaultMessage();
            ra.addFlashAttribute("flashError", errorMessage);
            return "redirect:/coupons";
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            couponFacade.claim(actor.email(), form.getCode());
            ra.addFlashAttribute("flashSuccess", "쿠폰이 발급되었습니다.");
        } catch (BusinessException e) {
            log.warn("쿠폰 발급 실패: code={}, reason={}", form.getCode(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/coupons";
    }
}
