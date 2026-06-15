package com.shop.shop.web.review;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.product.spi.ReviewFacade;
import com.shop.shop.web.support.CurrentActorResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;

/**
 * 리뷰 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 /reviews/** → hasRole("CONSUMER").
 * 미인증 → 302 /login (컨트롤러 도달 전 Security 처리).
 *
 * <p>레이어: ReviewViewController → {@link ReviewFacade}(product.spi published port).
 * product 도메인 내부 Service·Entity·Repository 직접 참조 금지.
 *
 * <p>PRG 패턴:
 * <ul>
 *   <li>작성 성공 → redirect:/products/{productId}?review + flash</li>
 *   <li>수정 성공 → redirect:/products/{productId}?review + flash</li>
 *   <li>삭제 성공 → redirect:/products/{productId}</li>
 *   <li>검증/도메인 예외 → 폼 재렌더(입력 보존)</li>
 * </ul>
 *
 * <p>모델 키: reviewForm (Thymeleaf 예약어 회피 — request/param/application/session 금지).
 */
@Controller
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewViewController {

    private static final String FORM_VIEW = "review/form";

    private final ReviewFacade reviewFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 리뷰 작성 폼 표시.
     * GET /reviews/new?orderItemId=
     *
     * @param orderItemId 주문 항목 ID (쿼리 파라미터)
     * @param model       Spring MVC 모델
     * @return view name "review/form"
     */
    @GetMapping("/new")
    public String newForm(
            @RequestParam Long orderItemId,
            Model model) {

        ReviewForm reviewForm = new ReviewForm();
        reviewForm.setOrderItemId(orderItemId);
        model.addAttribute("reviewForm", reviewForm);
        return FORM_VIEW;
    }

    /**
     * 리뷰 작성 제출.
     * POST /reviews → 성공 redirect /products/{productId}?review + flash.
     * 실패(BindingResult/BusinessException) → 폼 재렌더(입력 보존).
     *
     * @param auth               Spring Security 인증 객체 (username=email)
     * @param form               폼 백킹 객체
     * @param bindingResult      검증 결과
     * @param redirectAttributes Flash 속성
     * @param model              Spring MVC 모델
     * @return redirect 또는 view name
     */
    @PostMapping
    public String create(
            Authentication auth,
            @Valid @ModelAttribute("reviewForm") ReviewForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            return FORM_VIEW;
        }

        try {
            String email = currentActorResolver.resolve(auth).email();
            long productId = reviewFacade.create(
                    email,
                    form.getOrderItemId(),
                    form.getRating(),
                    form.getContent()
            );
            redirectAttributes.addFlashAttribute("flashSuccess", "리뷰가 작성되었습니다.");
            return "redirect:/products/" + productId + "?review";
        } catch (BusinessException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return FORM_VIEW;
        }
    }

    /**
     * 리뷰 수정 제출.
     * POST /reviews/{id}/edit → 성공 redirect /products/{productId}?review + flash.
     *
     * @param auth               Spring Security 인증 객체 (username=email)
     * @param reviewId           수정할 리뷰 ID
     * @param form               폼 백킹 객체
     * @param bindingResult      검증 결과
     * @param redirectAttributes Flash 속성
     * @param model              Spring MVC 모델
     * @return redirect 또는 view name
     */
    @PostMapping("/{id}/edit")
    public String edit(
            Authentication auth,
            @PathVariable("id") long reviewId,
            @Valid @ModelAttribute("reviewForm") ReviewForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            form.setReviewId(reviewId);
            return FORM_VIEW;
        }

        try {
            String email = currentActorResolver.resolve(auth).email();
            long productId = reviewFacade.edit(
                    email,
                    reviewId,
                    form.getRating(),
                    form.getContent()
            );
            redirectAttributes.addFlashAttribute("flashSuccess", "리뷰가 수정되었습니다.");
            return "redirect:/products/" + productId + "?review";
        } catch (BusinessException e) {
            model.addAttribute("errorMessage", e.getMessage());
            form.setReviewId(reviewId);
            return FORM_VIEW;
        }
    }

    /**
     * 리뷰 삭제 제출.
     * POST /reviews/{id}/delete → 성공 redirect /products/{productId}.
     *
     * @param auth               Spring Security 인증 객체 (username=email)
     * @param reviewId           삭제할 리뷰 ID
     * @param redirectAttributes Flash 속성
     * @return redirect
     */
    @PostMapping("/{id}/delete")
    public String delete(
            Authentication auth,
            @PathVariable("id") long reviewId,
            RedirectAttributes redirectAttributes) {

        String email = currentActorResolver.resolve(auth).email();
        long productId = reviewFacade.delete(email, reviewId);
        redirectAttributes.addFlashAttribute("flashSuccess", "리뷰가 삭제되었습니다.");
        return "redirect:/products/" + productId;
    }
}
