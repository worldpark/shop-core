package com.shop.shop.view;

import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.PublicCategoryResponse;
import com.shop.shop.product.dto.PublicOptionValueResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductImageResponse;
import com.shop.shop.product.dto.PublicProductOptionResponse;
import com.shop.shop.product.dto.PublicProductVariantResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 상품 상세 화면 Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/product/detail.html)이 layout/base·프래그먼트와 함께
 * 올바르게 렌더링되는지 검증한다.
 *
 * <p>PublicProductFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>비인증 200</li>
 *   <li>이미지 갤러리 렌더링</li>
 *   <li>상품명·설명·가격 렌더링</li>
 *   <li>옵션/옵션값 렌더링</li>
 *   <li>활성 variant 목록 렌더링</li>
 *   <li>soldOut 표시</li>
 *   <li>장바구니 버튼 비활성(준비중) 표시</li>
 *   <li>미존재·비공개 → error 뷰(404)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PublicProductDetailRenderingTest {

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

    @MockitoBean
    private CartRepository cartRepository;

    @MockitoBean
    private CartItemRepository cartItemRepository;

    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private PublicProductFacade publicProductFacade;

    private static final long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() {
        // 기본 상세 stub
        when(publicProductFacade.getProductDetail(PRODUCT_ID))
                .thenReturn(sampleDetailResponse(PRODUCT_ID, false));
    }

    // ============================================================
    // (D1) 비인증 200
    // ============================================================

    @Test
    @DisplayName("(D1) GET /products/{id} — 비인증 → 200")
    void getProductDetail_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk());
    }

    // ============================================================
    // (D2) 공통 레이아웃
    // ============================================================

    @Test
    @DisplayName("(D2) GET /products/{id} — 공통 레이아웃(header·nav·footer) 포함")
    void getProductDetail_includesLayoutFragments() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("header(shop-core)가 포함되어야 함").contains("shop-core");
        assertThat(body).as("footer 마커가 포함되어야 함").contains("2026 shop-core. All rights reserved.");
    }

    // ============================================================
    // (D3) 이미지 갤러리
    // ============================================================

    @Test
    @DisplayName("(D3) GET /products/{id} — 이미지 갤러리 렌더링(imageUrl)")
    void getProductDetail_rendersImageGallery() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("이미지 URL이 갤러리에 있어야 함")
                .contains("http://localhost:8080/assets/products/1/img.jpg");
    }

    @Test
    @DisplayName("(D3-multi) GET /products/{id} — 이미지 여러 장이면 썸네일 목록 렌더링")
    void getProductDetail_multipleImages_rendersThumbnails() throws Exception {
        PublicProductImageResponse img1 = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/img1.jpg", 0, true);
        PublicProductImageResponse img2 = new PublicProductImageResponse(
                101L, "http://localhost:8080/assets/products/1/img2.jpg", 1, false);
        PublicProductDetailResponse detail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(img1, img2), List.of(), List.of()
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("첫 번째 이미지 URL이 있어야 함")
                .contains("http://localhost:8080/assets/products/1/img1.jpg");
        assertThat(body).as("두 번째 이미지 URL이 있어야 함")
                .contains("http://localhost:8080/assets/products/1/img2.jpg");
    }

    // ============================================================
    // (D4) 상품 기본 정보
    // ============================================================

    @Test
    @DisplayName("(D4) GET /products/{id} — 상품명·설명·가격 렌더링")
    void getProductDetail_rendersBasicInfo() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명이 있어야 함").contains("테스트 상품");
        assertThat(body).as("상품 설명이 있어야 함").contains("상세 설명 텍스트");
        assertThat(body).as("가격이 있어야 함").contains("10,000");
    }

    @Test
    @DisplayName("(D4-category) GET /products/{id} — 카테고리명 렌더링")
    void getProductDetail_rendersCategoryName() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("카테고리명이 있어야 함").contains("전자기기");
    }

    // ============================================================
    // (D5) 옵션/옵션값
    // ============================================================

    @Test
    @DisplayName("(D5) GET /products/{id} — 옵션명·옵션값 렌더링")
    void getProductDetail_rendersOptions() throws Exception {
        PublicOptionValueResponse val1 = new PublicOptionValueResponse(10L, "빨강");
        PublicOptionValueResponse val2 = new PublicOptionValueResponse(11L, "파랑");
        PublicProductOptionResponse option = new PublicProductOptionResponse(50L, "색상", List.of(val1, val2));
        PublicProductDetailResponse detail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(new PublicProductImageResponse(100L, "http://localhost:8080/assets/img.jpg", 0, true)),
                List.of(option), List.of()
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("옵션명 '색상'이 있어야 함").contains("색상");
        assertThat(body).as("옵션값 '빨강'이 있어야 함").contains("빨강");
        assertThat(body).as("옵션값 '파랑'이 있어야 함").contains("파랑");
    }

    // ============================================================
    // (D6) Variant 목록
    // ============================================================

    @Test
    @DisplayName("(D6) GET /products/{id} — 활성 variant 가격·구매가능 표시 렌더링")
    void getProductDetail_rendersVariants() throws Exception {
        PublicProductVariantResponse availableVariant = new PublicProductVariantResponse(
                300L, new BigDecimal("12000"), List.of(), true);
        PublicProductVariantResponse unavailableVariant = new PublicProductVariantResponse(
                301L, new BigDecimal("15000"), List.of(), false);
        PublicProductDetailResponse detail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("12000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(new PublicProductImageResponse(100L, "http://localhost:8080/assets/img.jpg", 0, true)),
                List.of(), List.of(availableVariant, unavailableVariant)
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variant 가격 12,000이 있어야 함").contains("12,000");
        assertThat(body).as("variant 가격 15,000이 있어야 함").contains("15,000");
        assertThat(body).as("구매 가능 배지가 있어야 함").contains("구매 가능");
        assertThat(body).as("구매 불가 배지가 있어야 함").contains("구매 불가");
    }

    // ============================================================
    // (D7) soldOut 표시
    // ============================================================

    @Test
    @DisplayName("(D7) GET /products/{id} — 품절 상품(soldOut=true)에 '품절' 표시")
    void getProductDetail_soldOut_rendersSoldOutBadge() throws Exception {
        when(publicProductFacade.getProductDetail(PRODUCT_ID))
                .thenReturn(sampleDetailResponse(PRODUCT_ID, true));

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("품절 배지가 있어야 함").contains("품절");
    }

    @Test
    @DisplayName("(D7-onsale) GET /products/{id} — 구매 가능 상품(soldOut=false)에 '구매 가능' 표시")
    void getProductDetail_onSale_rendersOnSaleBadge() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("구매 가능 배지가 있어야 함").contains("구매 가능");
    }

    // ============================================================
    // (D8) 장바구니 담기 영역 (비인증 → 로그인 안내)
    // ============================================================

    @Test
    @DisplayName("(D8) GET /products/{id} — 비인증 → 로그인 안내 렌더링")
    void getProductDetail_rendersLoginNoticeForUnauthenticated() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("로그인 후 담기 가능 텍스트가 있어야 함").contains("로그인 후 담기 가능");
    }

    // ============================================================
    // (D9) 비공개·미존재 → error 뷰 (404)
    // ============================================================

    @Test
    @DisplayName("(D9) GET /products/{id} — 미존재 상품 → 404")
    void getProductDetail_notFound_returns404() throws Exception {
        when(publicProductFacade.getProductDetail(999L))
                .thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(get("/products/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("(D9-hidden) GET /products/{id} — DRAFT/HIDDEN 상품(facade→404) → 404")
    void getProductDetail_draftOrHidden_returns404() throws Exception {
        when(publicProductFacade.getProductDetail(200L))
                .thenThrow(new ProductNotFoundException(200L));

        mockMvc.perform(get("/products/200"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("(D9-errorbody) GET /products/{id} — 404 응답 본문에 error 뷰 렌더링")
    void getProductDetail_notFound_rendersErrorView() throws Exception {
        when(publicProductFacade.getProductDetail(999L))
                .thenThrow(new ProductNotFoundException(999L));

        String body = mockMvc.perform(get("/products/999"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // ViewExceptionHandler → error/error 뷰
        assertThat(body).as("에러 페이지가 렌더링되어야 함").contains("404");
    }

    // ============================================================
    // (D3-primary) primary 이미지가 index 0이 아닌 케이스
    // ============================================================

    @Test
    @DisplayName("(D3-primary) GET /products/{id} — primary 이미지가 index 0이 아닐 때 메인 이미지 src가 primary imageUrl과 일치")
    void getProductDetail_primaryImageNotAtIndex0_rendersMainImageAsPrimary() throws Exception {
        // sortOrder=0, primary=false (index 0)
        PublicProductImageResponse nonPrimaryFirst = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/non-primary.jpg", 0, false);
        // sortOrder=1, primary=true (index 1)
        PublicProductImageResponse primarySecond = new PublicProductImageResponse(
                101L, "http://localhost:8080/assets/products/1/primary.jpg", 1, true);
        PublicProductDetailResponse detail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(nonPrimaryFirst, primarySecond), List.of(), List.of()
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // id="main-product-image" 요소의 src가 primary 이미지 URL 이어야 함
        assertThat(body).as("main-product-image의 src가 primary 이미지 URL이어야 함")
                .contains("id=\"main-product-image\"");
        int mainImageIdx = body.indexOf("id=\"main-product-image\"");
        // main-product-image img 태그 앞부분에 primary URL이 src로 포함되어야 함
        String surroundingHtml = body.substring(Math.max(0, mainImageIdx - 200), mainImageIdx + 100);
        assertThat(surroundingHtml).as("main-product-image의 src가 primary 이미지 URL이어야 함")
                .contains("primary.jpg");
        assertThat(surroundingHtml).as("main-product-image의 src가 non-primary 이미지 URL이면 안 됨")
                .doesNotContain("non-primary.jpg");
    }

    // ============================================================
    // (D10) nav /products 링크
    // ============================================================

    @Test
    @DisplayName("(D10) GET /products/{id} — nav에 /products 링크 노출")
    void getProductDetail_navContainsProductsLink() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /products 링크가 있어야 함").contains("/products");
        assertThat(body).as("nav에 상품 목록 텍스트가 있어야 함").contains("상품 목록");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private PublicProductDetailResponse sampleDetailResponse(long productId, boolean soldOut) {
        PublicProductImageResponse image = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/img.jpg", 0, true);
        PublicProductOptionResponse option = new PublicProductOptionResponse(
                50L, "색상", List.of(new PublicOptionValueResponse(10L, "빨강")));
        PublicProductVariantResponse variant = new PublicProductVariantResponse(
                300L, new BigDecimal("10000"), List.of(), !soldOut);
        return new PublicProductDetailResponse(
                productId, "테스트 상품", "상세 설명 텍스트",
                new BigDecimal("10000"), soldOut,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(image), List.of(option), List.of(variant)
        );
    }
}
