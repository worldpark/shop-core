package com.shop.shop.web.product;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductForm;
import com.shop.shop.product.dto.ProductFormView;
import com.shop.shop.product.spi.SellerProductFacade;
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

import java.util.List;

/**
 * SELLER 상품 등록/수정 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>principal 통일(View): form login session principal = UserDetails(username=email).
 * {@code auth.getName()} → facade 내부에서 {@code UserDirectory.findUserIdByEmail}로 actorId 획득.
 * {@code actorIsAdmin}: authority 'ROLE_ADMIN' 직접 보유로 판정 (순수 Spring Security 로직 — web 잔존).
 *
 * <p>레이어: SellerProductViewController → {@link SellerProductFacade}(published port)
 * → ProductService/CategoryService → Repository.
 * 모델엔 DTO/ViewModel·폼 객체만 담는다 (Entity·enum 금지).
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code productForm} — {@link ProductForm} (@ModelAttribute + 수동 설정)</li>
 *   <li>{@code categories} — {@code List<CategoryResponse>} (flat 목록)</li>
 *   <li>{@code statuses} — {@code List<String>} (ProductStatus.name() 목록)</li>
 *   <li>{@code productId} — {@code long} (수정 화면용)</li>
 * </ul>
 * View name: {@code seller/product-form}
 * 성공 redirect: {@code redirect:/seller/products/{id}/edit}
 *
 * <p>원래 {@code product.controller.SellerProductViewController}에서 {@code web.product}로 이동.
 * {@code ProductService}·{@code CategoryService}·{@code UserDirectory}·{@code Product}·{@code ProductStatus}
 * 직접 의존 제거 → {@link SellerProductFacade} 사용.
 */
@Slf4j
@Controller
@RequestMapping("/seller/products")
@RequiredArgsConstructor
public class SellerProductViewController {

    private static final String PRODUCT_FORM_VIEW = "seller/product-form";

    private final SellerProductFacade sellerProductFacade;

    /**
     * 상품 등록 화면.
     * GET /seller/products/new
     *
     * <p>빈 ProductForm + categories(List&lt;CategoryResponse&gt;) + statuses(List&lt;String&gt;) 모델 바인딩.
     *
     * @param model Spring MVC 모델
     * @return view name "seller/product-form"
     */
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("productForm", new ProductForm());
        populateFormModel(model);
        return PRODUCT_FORM_VIEW;
    }

    /**
     * 상품 등록 처리.
     * POST /seller/products
     *
     * <p>검증 실패: categories/statuses 재주입 → "seller/product-form" 재렌더(입력값·메시지 유지).
     * 성공: actorEmail = auth.getName() → facade.register → 신규 productId 반환
     *      → redirect:/seller/products/{id}/edit (PRG 패턴).
     *
     * @param form          폼 백킹 객체 (모델 키 "productForm")
     * @param bindingResult 검증 결과 (반드시 form 파라미터 바로 다음 위치)
     * @param auth          SecurityContext 인증 객체 (username=email, form login session)
     * @param model         Spring MVC 모델
     * @return view name 또는 redirect
     */
    @PostMapping
    public String register(
            @Valid @ModelAttribute("productForm") ProductForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model) {

        if (bindingResult.hasErrors()) {
            populateFormModel(model);
            return PRODUCT_FORM_VIEW;
        }

        long id = sellerProductFacade.register(
                auth.getName(),
                form.getCategoryId(),
                form.getName(),
                form.getDescription(),
                form.getBasePrice()
        );

        return "redirect:/seller/products/" + id + "/edit";
    }

    /**
     * 상품 수정 화면.
     * GET /seller/products/{id}/edit
     *
     * <p>소유권 검사 포함(타인/미존재 → ProductAccessDeniedException/ProductNotFoundException →
     * ViewExceptionHandler error/error).
     *
     * @param id    수정할 상품 ID
     * @param auth  SecurityContext 인증 객체
     * @param model Spring MVC 모델
     * @return view name "seller/product-form"
     */
    @GetMapping("/{id}/edit")
    public String editForm(
            @PathVariable long id,
            Authentication auth,
            Model model) {

        ProductFormView view = sellerProductFacade.getForEdit(auth.getName(), isAdmin(auth), id);

        ProductForm form = toForm(view);
        model.addAttribute("productForm", form);
        model.addAttribute("productId", id);
        populateFormModel(model);
        return PRODUCT_FORM_VIEW;
    }

    /**
     * 상품 수정 처리.
     * POST /seller/products/{id}
     *
     * <p>검증 실패: categories/statuses 재주입 → "seller/product-form" 재렌더.
     * 성공: facade.update → redirect:/seller/products/{id}/edit.
     *
     * @param id            수정할 상품 ID
     * @param form          폼 백킹 객체 (모델 키 "productForm")
     * @param bindingResult 검증 결과
     * @param auth          SecurityContext 인증 객체
     * @param model         Spring MVC 모델
     * @return view name 또는 redirect
     */
    @PostMapping("/{id}")
    public String update(
            @PathVariable long id,
            @Valid @ModelAttribute("productForm") ProductForm form,
            BindingResult bindingResult,
            Authentication auth,
            Model model) {

        // 수정 경로: status 누락은 검증 실패로 처리(기존 상태를 DRAFT로 덮지 않음)
        if (form.getStatus() == null) {
            bindingResult.rejectValue("status", "NotNull", "상품 상태는 필수입니다.");
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("productId", id);
            populateFormModel(model);
            return PRODUCT_FORM_VIEW;
        }

        sellerProductFacade.update(
                auth.getName(),
                isAdmin(auth),
                id,
                form.getCategoryId(),
                form.getName(),
                form.getDescription(),
                form.getBasePrice(),
                form.getStatus()
        );

        return "redirect:/seller/products/" + id + "/edit";
    }

    /**
     * 폼 공통 모델 데이터 주입.
     * categories(List&lt;CategoryResponse&gt;) + statuses(List&lt;String&gt;).
     */
    private void populateFormModel(Model model) {
        List<CategoryResponse> categories = sellerProductFacade.listCategories();
        List<String> statuses = sellerProductFacade.productStatusNames();
        model.addAttribute("categories", categories);
        model.addAttribute("statuses", statuses);
    }

    /**
     * {@link ProductFormView} → {@link ProductForm} 변환 (수정 화면용).
     * Entity를 모델에 직접 담지 않음(Constraint).
     * status는 String으로 그대로 매핑.
     */
    private ProductForm toForm(ProductFormView view) {
        ProductForm form = new ProductForm();
        form.setCategoryId(view.categoryId());
        form.setName(view.name());
        form.setDescription(view.description());
        form.setBasePrice(view.basePrice());
        form.setStatus(view.status());
        return form;
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     * RoleHierarchy 함의가 아닌 원본 ROLE_ADMIN 직접 보유로 판정.
     * 순수 Spring Security 로직이라 web에 잔존.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
