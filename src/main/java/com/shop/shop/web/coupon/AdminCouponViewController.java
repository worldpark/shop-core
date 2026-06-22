package com.shop.shop.web.coupon;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.spi.AdminCouponFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 관리자 쿠폰 관리 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작). 컨트롤러 권한 분기 금지(041 동일).
 *
 * <p>레이어: AdminCouponViewController(@Controller)
 * → {@link AdminCouponFacade}(order.spi published port)
 * → CouponService/CouponRepository.
 * web은 order 내부(domain·service·repository) 직접 참조 금지.
 *
 * <p>모델 키 계약:
 * <ul>
 *   <li>{@code coupons} — {@code List<AdminCouponResponse>} (쿠폰 목록·사용현황)</li>
 *   <li>{@code couponForm} — {@link AdminCouponCreateForm} (등록 폼 Backing Object)</li>
 * </ul>
 * View name: {@code admin/coupons}, redirect: {@code redirect:/admin/coupons}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 *
 * <p>선례: AdminCategoryViewController (041) PRG 패턴 복제.
 */
@Slf4j
@Controller
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponViewController {

    private final AdminCouponFacade adminCouponFacade;

    /**
     * 쿠폰 목록 + 등록 폼 화면.
     * GET /admin/coupons
     *
     * <p>전체 쿠폰 정의(사용현황·활성여부 포함)를 조회하고 등록 폼(couponForm)을 모델에 추가한다.
     *
     * @param model Spring MVC 모델
     * @return view name "admin/coupons"
     */
    @GetMapping
    public String list(Model model) {
        List<AdminCouponResponse> coupons = adminCouponFacade.list();
        model.addAttribute("coupons", coupons);

        // 폼 에러 재렌더 시 RedirectAttributes에서 넘어온 couponForm이 이미 있을 수 있음
        if (!model.containsAttribute("couponForm")) {
            model.addAttribute("couponForm", new AdminCouponCreateForm());
        }

        return "admin/coupons";
    }

    /**
     * 쿠폰 정의 등록 폼 제출.
     * POST /admin/coupons
     *
     * <p>검증 실패 시: 목록 재조회 후 폼 에러와 함께 동일 뷰 재렌더링 (PRG 아님 — 041 패턴).
     * 성공 시: flashSuccess + {@code redirect:/admin/coupons} (PRG 패턴).
     * DuplicateCouponCodeException·BusinessException 발생 시: flashError + redirect.
     *
     * <p>form → AdminCouponCreateRequest 변환: web 타입을 facade에 직접 전달하지 않는다
     * (architecture-rule — OrderViewController 선례와 동형).
     *
     * @param form          등록 폼 (검증 어노테이션 적용)
     * @param bindingResult 폼 검증 결과
     * @param model         Spring MVC 모델 (재렌더 시 목록 추가)
     * @param ra            RedirectAttributes (flash 속성 전달)
     * @return view name 또는 redirect
     */
    @PostMapping
    public String create(
            @Valid @ModelAttribute("couponForm") AdminCouponCreateForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            // 폼 에러 재렌더 — 목록 재조회 (041 패턴)
            List<AdminCouponResponse> coupons = adminCouponFacade.list();
            model.addAttribute("coupons", coupons);
            return "admin/coupons";
        }

        try {
            // form → AdminCouponCreateRequest 변환 (web 타입을 facade에 직접 전달 금지)
            AdminCouponCreateRequest req = new AdminCouponCreateRequest(
                    form.getCode(),
                    form.getName(),
                    form.getDiscountType(),
                    form.getValue(),
                    form.getMinOrderAmount(),
                    form.getMaxDiscount(),
                    form.parseStartsAt(),
                    form.parseEndsAt(),
                    form.getUsageLimit(),
                    form.getIsActive()
            );
            adminCouponFacade.create(req);
            ra.addFlashAttribute("flashSuccess", "쿠폰이 등록되었습니다.");
        } catch (BusinessException e) {
            log.warn("쿠폰 등록 실패: code={}, reason={}", form.getCode(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/coupons";
    }
}
