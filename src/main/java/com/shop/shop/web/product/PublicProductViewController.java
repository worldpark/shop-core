package com.shop.shop.web.product;

import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.spi.PublicProductFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 공개 상품 목록/상세 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 GET /products, GET /products/* → permitAll.
 * 인증 불필요 — 비인증 사용자도 접근 가능. Authentication/principal 미사용.
 *
 * <p>레이어: PublicProductViewController → {@link PublicProductFacade}(published port)
 * → PublicProductService → Repository.
 * 모델에는 DTO만 담는다 (Entity·내부 enum 금지).
 *
 * <p>모델 키 계약 (Backend-View Contract 준수):
 * <ul>
 *   <li>{@code products} — {@link PublicProductFacade.PublicProductPage} (목록)</li>
 *   <li>{@code searchCondition} — {@link ProductSearchCondition} (검색 조건)</li>
 *   <li>{@code categories} — {@code List<CategoryResponse>} (필터 셀렉트용)</li>
 *   <li>{@code product} — {@link PublicProductDetailResponse} (상세)</li>
 * </ul>
 *
 * <p>예외: 미존재·DRAFT·HIDDEN 상품 상세 → facade가 ProductNotFoundException(404) 발생
 * → ViewExceptionHandler → error/error 뷰. 컨트롤러에서 try-catch 하지 않는다.
 */
@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class PublicProductViewController {

    private static final String LIST_VIEW = "product/list";
    private static final String DETAIL_VIEW = "product/detail";

    private final PublicProductFacade publicProductFacade;

    /**
     * 공개 상품 목록 화면.
     * GET /products
     *
     * <p>검색 조건(keyword/categoryId/sort/page/size)을 ProductSearchCondition에 담아 모델에 주입.
     * products(PublicProductPage) + searchCondition + categories 모델 바인딩.
     *
     * @param keyword    상품명 검색어 (선택)
     * @param categoryId 카테고리 ID 필터 (선택)
     * @param sort       정렬 (latest/priceAsc/priceDesc, 기본 latest)
     * @param page       페이지 번호 (0-based, 기본 0)
     * @param size       페이지 크기 (기본 20)
     * @param model      Spring MVC 모델
     * @return view name "product/list"
     */
    @GetMapping
    public String listProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        // page/size 음수·과대 보정 (facade 내부에서도 처리하지만 뷰 모델 일관성을 위해)
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }

        ProductSearchCondition searchCondition = new ProductSearchCondition(keyword, categoryId, sort, page, size);

        PublicProductFacade.PublicProductPage products =
                publicProductFacade.listProducts(keyword, categoryId, sort, page, size);
        List<CategoryResponse> categories = publicProductFacade.listCategories();

        model.addAttribute("products", products);
        model.addAttribute("searchCondition", searchCondition);
        model.addAttribute("categories", categories);

        return LIST_VIEW;
    }

    /**
     * 공개 상품 상세 화면.
     * GET /products/{productId}
     *
     * <p>미존재·DRAFT·HIDDEN → facade가 ProductNotFoundException(404) 발생
     * → ViewExceptionHandler → error/error 뷰(status=404). 컨트롤러에서 처리하지 않음.
     *
     * @param productId 상품 ID
     * @param model     Spring MVC 모델
     * @return view name "product/detail"
     */
    @GetMapping("/{productId}")
    public String getProductDetail(
            @PathVariable long productId,
            Model model) {

        PublicProductDetailResponse product = publicProductFacade.getProductDetail(productId);
        model.addAttribute("product", product);

        return DETAIL_VIEW;
    }
}
