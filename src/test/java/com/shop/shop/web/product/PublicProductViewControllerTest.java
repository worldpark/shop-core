package com.shop.shop.web.product;

import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.PublicCategoryResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductImageResponse;
import com.shop.shop.product.dto.PublicProductOptionResponse;
import com.shop.shop.product.dto.PublicProductVariantResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.PublicProductFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * PublicProductViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>PublicProductFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>비인증 200 (permitAll)</li>
 *   <li>모델 키(products, searchCondition, categories) 주입 확인</li>
 *   <li>view name 확인</li>
 *   <li>검색 파라미터 전달 확인</li>
 *   <li>미존재·비공개 상품 → 404</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PublicProductViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OptionValueRepository optionValueRepository;

    @MockitoBean
    private ProductVariantRepository productVariantRepository;

    @MockitoBean
    private ProductImageRepository productImageRepository;

    /**
     * PublicProductFacade @MockitoBean — product.spi facade 격리.
     * product 도메인 내부 Service·Repository와 무관하게 컨트롤러만 테스트한다.
     */
    @MockitoBean
    private PublicProductFacade publicProductFacade;

    private static final long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() {
        // 기본 stub: 빈 목록
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(), 0, 20, 0L, 0));
        when(publicProductFacade.listCategories())
                .thenReturn(List.of());
    }

    // ============================================================
    // GET /products — 비인증 접근
    // ============================================================

    @Test
    @DisplayName("GET /products — 비인증 → 200 (permitAll)")
    void listProducts_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /products — 비인증 → view name product/list")
    void listProducts_unauthenticated_returnsListView() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"));
    }

    // ============================================================
    // GET /products — 모델 키
    // ============================================================

    @Test
    @DisplayName("GET /products — model에 products 존재")
    void listProducts_modelContainsProducts() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("products"));
    }

    @Test
    @DisplayName("GET /products — model에 searchCondition 존재")
    void listProducts_modelContainsSearchCondition() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("searchCondition"));
    }

    @Test
    @DisplayName("GET /products — model에 categories 존재")
    void listProducts_modelContainsCategories() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("categories"));
    }

    // ============================================================
    // GET /products — 검색 파라미터 전달
    // ============================================================

    @Test
    @DisplayName("GET /products — keyword/categoryId/sort/page/size 파라미터 → facade.listProducts에 전달")
    void listProducts_searchParams_delegatesToFacade() throws Exception {
        when(publicProductFacade.listProducts("키워드", 2L, "priceAsc", 1, 10))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(), 1, 10, 0L, 0));

        mockMvc.perform(get("/products")
                        .param("keyword", "키워드")
                        .param("categoryId", "2")
                        .param("sort", "priceAsc")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());

        verify(publicProductFacade).listProducts("키워드", 2L, "priceAsc", 1, 10);
    }

    @Test
    @DisplayName("GET /products — 파라미터 없으면 기본값(sort=latest, page=0, size=20)으로 facade 호출")
    void listProducts_defaultParams_callsFacadeWithDefaults() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk());

        verify(publicProductFacade).listProducts(isNull(), isNull(), anyString(), anyInt(), anyInt());
    }

    // ============================================================
    // GET /products — 카테고리 facade 호출
    // ============================================================

    @Test
    @DisplayName("GET /products — facade.listCategories() 호출됨")
    void listProducts_callsListCategories() throws Exception {
        CategoryResponse category = new CategoryResponse(1L, null, "전자기기", "electronics", 1);
        when(publicProductFacade.listCategories()).thenReturn(List.of(category));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk());

        verify(publicProductFacade).listCategories();
    }

    // ============================================================
    // GET /products/{productId} — 비인증 접근
    // ============================================================

    @Test
    @DisplayName("GET /products/{id} — 비인증 → 200 (permitAll)")
    void getProductDetail_unauthenticated_returns200() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(PRODUCT_ID);
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /products/{id} — 비인증 → view name product/detail")
    void getProductDetail_unauthenticated_returnsDetailView() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(PRODUCT_ID);
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("product/detail"));
    }

    // ============================================================
    // GET /products/{productId} — 모델 키
    // ============================================================

    @Test
    @DisplayName("GET /products/{id} — model에 product 존재")
    void getProductDetail_modelContainsProduct() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(PRODUCT_ID);
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("product"));
    }

    // ============================================================
    // GET /products/{productId} — 404 처리
    // ============================================================

    @Test
    @DisplayName("GET /products/{id} — 미존재 상품 → 404 (ProductNotFoundException)")
    void getProductDetail_notFound_returns404() throws Exception {
        when(publicProductFacade.getProductDetail(999L))
                .thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(get("/products/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /products/{id} — DRAFT/HIDDEN 상품 → 404 (facade가 ProductNotFoundException 발생)")
    void getProductDetail_draftOrHidden_returns404() throws Exception {
        when(publicProductFacade.getProductDetail(100L))
                .thenThrow(new ProductNotFoundException(100L));

        mockMvc.perform(get("/products/100"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /products/{id} — 상세 facade.getProductDetail(productId) 호출 검증")
    void getProductDetail_callsFacadeWithProductId() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(PRODUCT_ID);
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk());

        verify(publicProductFacade).getProductDetail(PRODUCT_ID);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private PublicProductDetailResponse sampleDetailResponse(long productId) {
        PublicProductImageResponse image = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/img.jpg", 0, true);
        PublicProductOptionResponse option = new PublicProductOptionResponse(
                50L, "색상", List.of());
        PublicProductVariantResponse variant = new PublicProductVariantResponse(
                300L, new BigDecimal("10000"), List.of(), true);
        return new PublicProductDetailResponse(
                productId, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(image), List.of(option), List.of(variant)
        );
    }
}
