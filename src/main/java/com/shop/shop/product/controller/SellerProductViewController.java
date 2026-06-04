package com.shop.shop.product.controller;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductForm;
import com.shop.shop.product.service.CategoryService;
import com.shop.shop.product.service.ProductService;
import com.shop.shop.product.spi.UserDirectory;
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
import java.util.stream.Collectors;

/**
 * SELLER 상품 등록/수정 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>principal 통일(View): form login session principal = UserDetails(username=email).
 * {@code userDirectory.findUserIdByEmail(auth.getName())}으로 actorId 획득
 * (product 소유 {@link UserDirectory} 포트 — member 직접 호출 없음, 의존 역전).
 * {@code actorIsAdmin}: authority 'ROLE_ADMIN' 직접 보유로 판정.
 *
 * <p>레이어: SellerProductViewController → ProductService/CategoryService → Repository
 * (ServiceResponse 미사용 — architecture-rule: View ViewController → Service 직접).
 * 모델엔 DTO/ViewModel·enum·폼 객체만 담는다 (Entity 금지).
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code productForm} — {@link ProductForm} (@ModelAttribute + 수동 설정)</li>
 *   <li>{@code categories} — {@code List<CategoryResponse>} (flat 목록)</li>
 *   <li>{@code statuses} — {@code ProductStatus[]} (ProductStatus.values())</li>
 * </ul>
 * View name: {@code seller/product-form}
 * 성공 redirect: {@code redirect:/seller/products/{id}/edit}
 */
@Slf4j
@Controller
@RequestMapping("/seller/products")
@RequiredArgsConstructor
public class SellerProductViewController {

    private static final String PRODUCT_FORM_VIEW = "seller/product-form";

    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserDirectory userDirectory;

    /**
     * 상품 등록 화면.
     * GET /seller/products/new
     *
     * <p>빈 ProductForm + categories(List&lt;CategoryResponse&gt;) + statuses(ProductStatus[]) 모델 바인딩.
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
     * 성공: actorId = userDirectory.findUserIdByEmail(auth.getName()) → productService.register
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

        long actorId = userDirectory.findUserIdByEmail(auth.getName());

        Product product = productService.register(
                actorId,
                form.getCategoryId(),
                form.getName(),
                form.getDescription(),
                form.getBasePrice()
        );

        return "redirect:/seller/products/" + product.getId() + "/edit";
    }

    /**
     * 상품 수정 화면.
     * GET /seller/products/{id}/edit
     *
     * <p>소유권 검사 포함(타인/미존재 → ProductAccessDeniedException/ProductNotFoundException → ViewExceptionHandler error/error).
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

        long actorId = userDirectory.findUserIdByEmail(auth.getName());
        boolean actorIsAdmin = isAdmin(auth);

        Product product = productService.getForEdit(actorId, actorIsAdmin, id);

        ProductForm form = toForm(product);
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
     * 성공: productService.update → redirect:/seller/products/{id}/edit.
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

        long actorId = userDirectory.findUserIdByEmail(auth.getName());
        boolean actorIsAdmin = isAdmin(auth);

        ProductStatus status = form.getStatus();

        productService.update(
                actorId,
                actorIsAdmin,
                id,
                form.getCategoryId(),
                form.getName(),
                form.getDescription(),
                form.getBasePrice(),
                status
        );

        return "redirect:/seller/products/" + id + "/edit";
    }

    /**
     * 폼 공통 모델 데이터 주입.
     * categories(List&lt;CategoryResponse&gt;) + statuses(ProductStatus[]).
     */
    private void populateFormModel(Model model) {
        List<CategoryResponse> categories = categoryService.list().stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList());
        model.addAttribute("categories", categories);
        model.addAttribute("statuses", ProductStatus.values());
    }

    /**
     * Product Entity → ProductForm 변환 (수정 화면용).
     * Entity를 모델에 직접 담지 않음(Constraint).
     */
    private ProductForm toForm(Product product) {
        ProductForm form = new ProductForm();
        form.setCategoryId(product.getCategory() == null ? null : product.getCategory().getId());
        form.setName(product.getName());
        form.setDescription(product.getDescription());
        form.setBasePrice(product.getBasePrice());
        form.setStatus(product.getStatus());
        return form;
    }

    /**
     * ROLE_ADMIN 직접 보유 여부 판정.
     * RoleHierarchy 함의가 아닌 원본 ROLE_ADMIN 직접 보유로 판정.
     */
    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
