package com.shop.shop.view;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
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
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 상품 목록 화면 Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/product/list.html)이 layout/base·프래그먼트와 함께
 * 올바르게 렌더링되는지 검증한다.
 *
 * <p>PublicProductFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>비인증 200</li>
 *   <li>검색 폼(keyword/categoryId/sort) 렌더링</li>
 *   <li>카테고리 필터 셀렉트 렌더링</li>
 *   <li>정렬 컨트롤(최신/낮은가격/높은가격) 렌더링</li>
 *   <li>상품 카드(이미지·이름·가격·품절) 렌더링</li>
 *   <li>대표 이미지 없는 카드 placeholder 렌더링</li>
 *   <li>pagination UI 렌더링</li>
 *   <li>nav에 /products 링크 노출</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PublicProductListRenderingTest {

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
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PublicProductFacade publicProductFacade;

    @BeforeEach
    void setUp() {
        // 기본 stub: 카테고리 비어있음, 빈 상품 목록
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(), 0, 20, 0L, 0));
        when(publicProductFacade.listCategories())
                .thenReturn(List.of());
    }

    // ============================================================
    // (L1) 비인증 200
    // ============================================================

    @Test
    @DisplayName("(L1) GET /products — 비인증 → 200")
    void listProducts_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // (L2) 공통 레이아웃·nav
    // ============================================================

    @Test
    @DisplayName("(L2) GET /products — 공통 레이아웃(header·nav·footer) 포함")
    void listProducts_includesLayoutFragments() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("header(shop-core)가 포함되어야 함").contains("shop-core");
        assertThat(body).as("nav '홈' 마커가 포함되어야 함").contains("홈");
        assertThat(body).as("footer 마커가 포함되어야 함").contains("2026 shop-core. All rights reserved.");
        assertThat(body).as("/css/app.css 링크가 포함되어야 함").contains("/css/app.css");
    }

    @Test
    @DisplayName("(L2-nav) GET /products — nav에 /products 링크가 노출됨 (비인증 포함)")
    void listProducts_navContainsProductsLink() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /products 링크가 있어야 함").contains("/products");
        assertThat(body).as("nav에 상품 목록 텍스트가 있어야 함").contains("상품 목록");
    }

    // ============================================================
    // (L3) 검색 폼
    // ============================================================

    @Test
    @DisplayName("(L3) GET /products — 검색 폼(keyword 입력) 렌더링")
    void listProducts_rendersSearchForm() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("keyword 입력 필드가 있어야 함").contains("id=\"keyword\"");
        assertThat(body).as("검색 폼 method=get이어야 함").contains("method=\"get\"");
    }

    // ============================================================
    // (L4) 카테고리 필터 셀렉트
    // ============================================================

    @Test
    @DisplayName("(L4) GET /products — 카테고리 필터 셀렉트(전체 카테고리 옵션) 렌더링")
    void listProducts_rendersCategoryFilterSelect() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("categoryId 셀렉트가 있어야 함").contains("id=\"categoryId\"");
        assertThat(body).as("전체 카테고리 기본 옵션이 있어야 함").contains("전체 카테고리");
    }

    @Test
    @DisplayName("(L4-stub) GET /products — 카테고리 stub 있으면 셀렉트에 옵션 렌더링")
    void listProducts_stubCategories_renderedInSelect() throws Exception {
        CategoryResponse category = new CategoryResponse(1L, null, "전자기기", "electronics", 1);
        when(publicProductFacade.listCategories()).thenReturn(List.of(category));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("카테고리명 '전자기기'가 셀렉트 옵션에 있어야 함").contains("전자기기");
    }

    // ============================================================
    // (L5) 정렬 컨트롤
    // ============================================================

    @Test
    @DisplayName("(L5) GET /products — 정렬 셀렉트(최신/낮은가격/높은가격) 렌더링")
    void listProducts_rendersSortControl() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("sort 셀렉트가 있어야 함").contains("id=\"sort\"");
        assertThat(body).as("최신순 옵션이 있어야 함").contains("최신순");
        assertThat(body).as("낮은 가격순 옵션이 있어야 함").contains("낮은 가격순");
        assertThat(body).as("높은 가격순 옵션이 있어야 함").contains("높은 가격순");
    }

    // ============================================================
    // (L6) 상품 카드 렌더링
    // ============================================================

    @Test
    @DisplayName("(L6) GET /products — 상품 카드(이미지·이름·가격·품절) 렌더링")
    void listProducts_rendersProductCard() throws Exception {
        PublicProductSummaryResponse product = new PublicProductSummaryResponse(
                1L, "테스트 상품", new BigDecimal("15000"), 1L, "전자기기",
                "http://localhost:8080/assets/products/1/img.jpg", false);
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(product), 0, 20, 1L, 1));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명이 카드에 있어야 함").contains("테스트 상품");
        assertThat(body).as("이미지 URL이 카드에 있어야 함")
                .contains("http://localhost:8080/assets/products/1/img.jpg");
        assertThat(body).as("가격이 카드에 있어야 함").contains("15");
        assertThat(body).as("구매 가능 배지가 있어야 함").contains("구매 가능");
    }

    @Test
    @DisplayName("(L6-soldout) GET /products — 품절 상품 카드에 '품절' 배지 렌더링")
    void listProducts_soldOutProduct_rendersSoldOutBadge() throws Exception {
        PublicProductSummaryResponse soldOutProduct = new PublicProductSummaryResponse(
                2L, "품절 상품", new BigDecimal("9000"), null, null, null, true);
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(soldOutProduct), 0, 20, 1L, 1));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("품절 배지가 있어야 함").contains("품절");
    }

    // ============================================================
    // (L7) 대표 이미지 없는 카드 placeholder
    // ============================================================

    @Test
    @DisplayName("(L7) GET /products — 대표 이미지 없는 상품 카드에 placeholder('이미지 없음') 렌더링")
    void listProducts_noImage_rendersPlaceholder() throws Exception {
        PublicProductSummaryResponse noImageProduct = new PublicProductSummaryResponse(
                3L, "이미지없는상품", new BigDecimal("5000"), null, null, null, false);
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(noImageProduct), 0, 20, 1L, 1));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("placeholder '이미지 없음' 텍스트가 있어야 함").contains("이미지 없음");
    }

    // ============================================================
    // (L8) pagination UI
    // ============================================================

    @Test
    @DisplayName("(L8) GET /products — 결과 있으면 pagination UI(이전/다음 버튼) 렌더링")
    void listProducts_withResults_rendersPagination() throws Exception {
        PublicProductSummaryResponse product = new PublicProductSummaryResponse(
                1L, "상품", new BigDecimal("10000"), null, null, null, false);
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(product), 0, 20, 1L, 1));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("pagination '이전' 버튼이 있어야 함").contains("이전");
        assertThat(body).as("pagination '다음' 버튼이 있어야 함").contains("다음");
    }

    @Test
    @DisplayName("(L8-total) GET /products — 총 상품 수 표시")
    void listProducts_showsTotalElements() throws Exception {
        PublicProductSummaryResponse product = new PublicProductSummaryResponse(
                1L, "상품", new BigDecimal("10000"), null, null, null, false);
        when(publicProductFacade.listProducts(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PublicProductFacade.PublicProductPage(List.of(product), 0, 20, 1L, 1));

        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("총 상품 수 텍스트가 있어야 함").contains("총");
    }

    // ============================================================
    // (L9) 빈 목록
    // ============================================================

    @Test
    @DisplayName("(L9) GET /products — 빈 목록이면 '조건에 맞는 상품이 없습니다' 렌더링")
    void listProducts_emptyResult_rendersEmptyMessage() throws Exception {
        String body = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("빈 목록 메시지가 있어야 함").contains("조건에 맞는 상품이 없습니다");
    }
}
