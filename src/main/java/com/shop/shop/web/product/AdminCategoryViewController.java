package com.shop.shop.web.product;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.spi.AdminCategoryFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 카테고리 관리 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>레이어: AdminCategoryViewController(@Controller)
 * → {@link AdminCategoryFacade}(published port)
 * → CategoryService → CategoryRepository.
 * web은 product.spi만 참조. product 내부(domain·service·repository) 직접 참조 금지.
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code categories} — {@code List<CategoryResponse>} (DTO, Entity 금지)</li>
 *   <li>{@code categoryNames} — {@code Map<Long,String>} (categoryId → name, 부모명 표기용)</li>
 *   <li>{@code categoryForm} — {@link CategoryForm} (등록 폼 Backing Object)</li>
 * </ul>
 * View name: {@code admin/categories}, redirect: {@code redirect:/admin/categories}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 *
 * <p>선례: {@link com.shop.shop.web.member.AdminMemberViewController} PRG 패턴 복제.
 */
@Slf4j
@Controller
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryViewController {

    private final AdminCategoryFacade adminCategoryFacade;

    /**
     * 카테고리 목록 + 등록 폼 화면.
     * GET /admin/categories
     *
     * <p>카테고리 전체 목록을 조회하고 등록 폼(categoryForm)을 모델에 추가한다.
     * categoryNames는 parentId(Long) → name 조회용 맵이다.
     *
     * @param model Spring MVC 모델
     * @return view name "admin/categories"
     */
    @GetMapping
    public String list(Model model) {
        List<CategoryResponse> categories = adminCategoryFacade.list();
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(CategoryResponse::categoryId, CategoryResponse::name));

        model.addAttribute("categories", categories);
        model.addAttribute("categoryNames", categoryNames);

        // 폼 에러 재렌더 시 RedirectAttributes에서 넘어온 categoryForm이 이미 있을 수 있음
        if (!model.containsAttribute("categoryForm")) {
            model.addAttribute("categoryForm", new CategoryForm());
        }

        return "admin/categories";
    }

    /**
     * 카테고리 등록 폼 제출.
     * POST /admin/categories
     *
     * <p>검증 실패 시: 목록 재조회 후 폼 에러와 함께 동일 뷰 재렌더링 (PRG 아님).
     * 성공 시: flashSuccess + {@code redirect:/admin/categories} (PRG 패턴).
     * DuplicateSlugException·CategoryNotFoundException 등 BusinessException 발생 시:
     * flashError + redirect.
     *
     * @param categoryForm 등록 폼 (검증 어노테이션 적용)
     * @param bindingResult 폼 검증 결과
     * @param model Spring MVC 모델 (재렌더 시 목록 추가)
     * @param ra RedirectAttributes (flash 속성 전달)
     * @return view name 또는 redirect
     */
    @PostMapping
    public String create(
            @Valid @ModelAttribute("categoryForm") CategoryForm categoryForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes ra) {

        if (bindingResult.hasErrors()) {
            // 폼 에러 재렌더 — 목록 재조회
            List<CategoryResponse> categories = adminCategoryFacade.list();
            Map<Long, String> categoryNames = categories.stream()
                    .collect(Collectors.toMap(CategoryResponse::categoryId, CategoryResponse::name));
            model.addAttribute("categories", categories);
            model.addAttribute("categoryNames", categoryNames);
            return "admin/categories";
        }

        try {
            adminCategoryFacade.create(
                    categoryForm.getName(),
                    categoryForm.getSlug(),
                    categoryForm.getParentId(),
                    categoryForm.getSortOrder()
            );
            ra.addFlashAttribute("flashSuccess", "카테고리가 등록되었습니다.");
        } catch (BusinessException e) {
            log.warn("카테고리 등록 실패: name={}, slug={}, reason={}", categoryForm.getName(), categoryForm.getSlug(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/categories";
    }

    /**
     * 카테고리 삭제 폼 제출.
     * POST /admin/categories/{categoryId}/delete
     *
     * <p>삭제 시 해당 상품은 미분류(category_id=NULL)로 전환되고,
     * 하위 카테고리는 최상위(parent_id=NULL)로 승격된다 (DB SET NULL).
     * 성공 시: flashSuccess + redirect. CategoryNotFoundException 등 실패 시: flashError + redirect.
     *
     * @param categoryId 삭제할 카테고리 ID
     * @param ra RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/categories
     */
    @PostMapping("/{categoryId}/delete")
    public String delete(
            @PathVariable long categoryId,
            RedirectAttributes ra) {

        try {
            adminCategoryFacade.delete(categoryId);
            ra.addFlashAttribute("flashSuccess", "카테고리가 삭제되었습니다. 해당 상품은 미분류로 전환됩니다.");
        } catch (BusinessException e) {
            log.warn("카테고리 삭제 실패: categoryId={}, reason={}", categoryId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/categories";
    }

    /**
     * 카테고리 등록 폼 Backing Object.
     *
     * <p>Entity 직접 바인딩 금지. 검증 어노테이션으로 서버 사이드 검증.
     * parentId는 선택(null = root 카테고리).
     */
    public static class CategoryForm {

        @NotBlank(message = "카테고리명은 필수입니다.")
        @Size(max = 100, message = "카테고리명은 100자 이하여야 합니다.")
        private String name;

        @NotBlank(message = "슬러그는 필수입니다.")
        @Size(max = 100, message = "슬러그는 100자 이하여야 합니다.")
        private String slug;

        @Min(value = 0, message = "정렬 순서는 0 이상이어야 합니다.")
        private int sortOrder;

        private Long parentId;

        public CategoryForm() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String slug) {
            this.slug = slug;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }
    }
}
