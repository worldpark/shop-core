package com.shop.shop.web.product;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.product.dto.VariantManagementView;
import com.shop.shop.product.spi.SellerProductVariantFacade;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;

/**
 * SELLER 상품 옵션/옵션값/Variant 관리 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>principal 통일(View): form login session principal = UserDetails(username=email).
 * {@link CurrentActorResolver}가 {@code auth.getName()}과 ROLE_ADMIN 직접 보유 여부를 추출한다.
 * facade 내부에서 {@code UserDirectory.findUserIdByEmail}로 actorId를 획득한다.
 *
 * <p>레이어: SellerProductVariantViewController → {@link SellerProductVariantFacade}(published port)
 * → ProductOptionService/ProductVariantService → Repository.
 * 모델엔 DTO/ViewModel·폼 객체만 담는다 (Entity·enum 금지).
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code product} — {@code SellerProductRef}</li>
 *   <li>{@code options} — {@code List&lt;ProductOptionResponse&gt;}</li>
 *   <li>{@code variants} — {@code List&lt;ProductVariantResponse&gt;}</li>
 *   <li>{@code optionForm} — {@link OptionForm}</li>
 *   <li>{@code optionValueForm} — {@link OptionValueForm}</li>
 *   <li>{@code variantForm} — {@link VariantForm}</li>
 * </ul>
 * View name: {@code seller/product-variants}
 * 성공/실패 redirect: {@code redirect:/seller/products/{productId}/variants}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 */
@Slf4j
@Controller
@RequestMapping("/seller/products/{productId}/variants")
@RequiredArgsConstructor
public class SellerProductVariantViewController {

    private static final String PRODUCT_VARIANTS_VIEW = "seller/product-variants";

    private final SellerProductVariantFacade sellerProductVariantFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * variant 관리 화면.
     * GET /seller/products/{productId}/variants
     *
     * <p>facade.getManagementView → product/options/variants 모델 키 분해 + 빈 폼 3종 추가.
     *
     * @param productId 대상 상품 ID
     * @param auth      SecurityContext 인증 객체
     * @param model     Spring MVC 모델
     * @return view name "seller/product-variants"
     */
    @GetMapping
    public String managementView(
            @PathVariable long productId,
            Authentication auth,
            Model model) {

        populateManagementModel(model, productId, auth);
        model.addAttribute("optionForm", new OptionForm());
        model.addAttribute("optionValueForm", new OptionValueForm());
        model.addAttribute("variantForm", new VariantForm());
        return PRODUCT_VARIANTS_VIEW;
    }

    /**
     * 옵션 생성 처리.
     * POST /seller/products/{productId}/options
     *
     * <p>Bean검증 실패 → 관리 모델 재조립 + 실패 optionForm 유지 + 나머지 빈 폼 → 재렌더.
     * 성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId     대상 상품 ID
     * @param form          옵션 생성 폼
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes
     * @return view name 또는 redirect
     */
    @PostMapping("/options")
    public String createOption(
            @PathVariable long productId,
            @Valid @ModelAttribute("optionForm") OptionForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            populateManagementModel(model, productId, auth);
            model.addAttribute("optionValueForm", new OptionValueForm());
            model.addAttribute("variantForm", new VariantForm());
            return PRODUCT_VARIANTS_VIEW;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductVariantFacade.createOption(
                    actor.email(), actor.admin(), productId, form.getName());
            ra.addFlashAttribute("flashSuccess", "옵션이 생성되었습니다.");
        } catch (BusinessException e) {
            log.warn("옵션 생성 실패: actorEmail={}, productId={}, reason={}", auth.getName(), productId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/variants";
    }

    /**
     * 옵션값 생성 처리.
     * POST /seller/products/{productId}/options/{optionId}/values
     *
     * <p>Bean검증 실패 → 관리 모델 재조립 + 실패 optionValueForm 유지 + 나머지 빈 폼 → 재렌더.
     * 성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId     대상 상품 ID
     * @param optionId      대상 옵션 ID
     * @param form          옵션값 생성 폼
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes
     * @return view name 또는 redirect
     */
    @PostMapping("/options/{optionId}/values")
    public String createOptionValue(
            @PathVariable long productId,
            @PathVariable long optionId,
            @Valid @ModelAttribute("optionValueForm") OptionValueForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            populateManagementModel(model, productId, auth);
            model.addAttribute("optionForm", new OptionForm());
            model.addAttribute("variantForm", new VariantForm());
            model.addAttribute("failedOptionValueOptionId", optionId);
            return PRODUCT_VARIANTS_VIEW;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductVariantFacade.createOptionValue(
                    actor.email(), actor.admin(), productId, optionId, form.getValue());
            ra.addFlashAttribute("flashSuccess", "옵션값이 생성되었습니다.");
        } catch (BusinessException e) {
            log.warn("옵션값 생성 실패: actorEmail={}, productId={}, optionId={}, reason={}",
                    auth.getName(), productId, optionId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/variants";
    }

    /**
     * Variant 생성 처리.
     * POST /seller/products/{productId}/variants
     *
     * <p>Bean검증 실패 → 관리 모델 재조립 + 실패 variantForm 유지 + 나머지 빈 폼 → 재렌더.
     * 성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId     대상 상품 ID
     * @param form          variant 생성 폼
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes
     * @return view name 또는 redirect
     */
    @PostMapping
    public String createVariant(
            @PathVariable long productId,
            @Valid @ModelAttribute("variantForm") VariantForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            populateManagementModel(model, productId, auth);
            model.addAttribute("optionForm", new OptionForm());
            model.addAttribute("optionValueForm", new OptionValueForm());
            return PRODUCT_VARIANTS_VIEW;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductVariantFacade.createVariant(
                    actor.email(), actor.admin(), productId,
                    form.getSku(), form.getPrice(), form.getStock(), form.isActive(),
                    form.getOptionValueIds() != null ? form.getOptionValueIds() : Collections.emptyList());
            ra.addFlashAttribute("flashSuccess", "Variant가 생성되었습니다.");
        } catch (BusinessException e) {
            log.warn("Variant 생성 실패: actorEmail={}, productId={}, reason={}", auth.getName(), productId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/variants";
    }

    /**
     * Variant 수정 처리.
     * POST /seller/products/{productId}/variants/{variantId}
     *
     * <p>Bean검증 실패 → 관리 모델 재조립 + 실패 variantForm 유지 + 나머지 빈 폼 → 재렌더.
     * 성공 → flashSuccess + PRG redirect.
     * BusinessException → flashError + redirect.
     *
     * @param productId     대상 상품 ID
     * @param variantId     수정할 variant ID
     * @param form          variant 수정 폼
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param model         Spring MVC 모델
     * @param ra            RedirectAttributes
     * @return view name 또는 redirect
     */
    @PostMapping("/{variantId}")
    public String updateVariant(
            @PathVariable long productId,
            @PathVariable long variantId,
            @Valid @ModelAttribute("variantForm") VariantForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            populateManagementModel(model, productId, auth);
            model.addAttribute("optionForm", new OptionForm());
            model.addAttribute("optionValueForm", new OptionValueForm());
            return PRODUCT_VARIANTS_VIEW;
        }

        try {
            CurrentActor actor = currentActorResolver.resolve(auth);
            sellerProductVariantFacade.updateVariant(
                    actor.email(), actor.admin(), productId, variantId,
                    form.getSku(), form.getPrice(), form.getStock(), form.isActive(),
                    form.getOptionValueIds() != null ? form.getOptionValueIds() : Collections.emptyList());
            ra.addFlashAttribute("flashSuccess", "Variant가 수정되었습니다.");
        } catch (BusinessException e) {
            log.warn("Variant 수정 실패: actorEmail={}, productId={}, variantId={}, reason={}",
                    auth.getName(), productId, variantId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/products/" + productId + "/variants";
    }

    /**
     * 관리 화면 공통 모델 데이터 주입.
     * product / options / variants 키를 facade.getManagementView 결과에서 분해해 주입한다.
     * 재렌더 시 세 폼이 모두 모델에 존재해야 하므로 호출 측이 나머지 폼을 추가해야 한다.
     */
    private void populateManagementModel(Model model, long productId, Authentication auth) {
        CurrentActor actor = currentActorResolver.resolve(auth);
        VariantManagementView view = sellerProductVariantFacade.getManagementView(
                actor.email(), actor.admin(), productId);
        model.addAttribute("product", view.product());
        model.addAttribute("options", view.options());
        model.addAttribute("variants", view.variants());
    }
}
