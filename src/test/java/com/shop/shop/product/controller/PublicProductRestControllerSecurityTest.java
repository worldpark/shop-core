package com.shop.shop.product.controller;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.PublicCategoryResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductImageResponse;
import com.shop.shop.product.dto.PublicProductOptionResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
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
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.service.PublicProductServiceResponse;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PublicProductRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test") + @MockitoBean serviceResponse.
 *
 * <p>검증 시나리오:
 * - GET /api/v1/products 비인증 200 (permitAll)
 * - GET /api/v1/products CONSUMER/SELLER/ADMIN 200
 * - GET /api/v1/products/{id} 비인증 200 (ON_SALE/SOLD_OUT)
 * - GET /api/v1/products/{id} DRAFT/HIDDEN/미존재 → 404
 * - 목록 검색·필터·정렬·pagination 응답 구조 검증
 * - 상세 응답에 images/options/variants 포함 검증
 * - 응답에 ownerId/storageKey/basePrice/sku 미포함 단언
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class PublicProductRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    SellerApplicationRepository sellerApplicationRepository;

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
    private PublicProductServiceResponse publicProductServiceResponse;

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
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ReviewRepository reviewRepository;

    @MockitoBean
    private com.shop.shop.order.adapter.OrderItemQueryRepository orderItemQueryRepository;


    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 stub: 빈 목록 응답
        when(publicProductServiceResponse.list(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));
    }

    // =============================================================
    // GET /api/v1/products — 비인증·역할별 200
    // =============================================================

    @Test
    @DisplayName("GET /api/v1/products — 비인증 → 200 (permitAll)")
    void getProducts_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/products — CONSUMER → 200")
    void getProducts_consumer_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/products — SELLER → 200")
    void getProducts_seller_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/products — ADMIN → 200")
    void getProducts_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // =============================================================
    // GET /api/v1/products — pagination 응답 구조
    // =============================================================

    @Test
    @DisplayName("GET /api/v1/products — 응답에 content/page/size/totalElements/totalPages 포함")
    void getProducts_hasPaginationMetadata() throws Exception {
        PublicProductSummaryResponse summary = new PublicProductSummaryResponse(
                1L, "테스트 상품", new BigDecimal("10000"), 2L, "전자기기",
                "http://localhost:8080/assets/products/1/img.jpg", false);

        when(publicProductServiceResponse.list(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(summary), 0, 20, 1L, 1));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].productId").value(1L))
                .andExpect(jsonPath("$.content[0].name").value("테스트 상품"))
                .andExpect(jsonPath("$.content[0].displayPrice").value(10000))
                .andExpect(jsonPath("$.content[0].soldOut").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/products — 응답에 ownerId/storageKey/basePrice/sku 미포함")
    void getProducts_doesNotContainSensitiveFields() throws Exception {
        PublicProductSummaryResponse summary = new PublicProductSummaryResponse(
                1L, "상품A", new BigDecimal("10000"), null, null, null, false);

        when(publicProductServiceResponse.list(any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(summary), 0, 20, 1L, 1));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].basePrice").doesNotExist())
                .andExpect(jsonPath("$.content[0].sku").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/products — 검색 파라미터(keyword, categoryId, sort, page, size) 응답 반환")
    void getProducts_withSearchParams_returns200() throws Exception {
        when(publicProductServiceResponse.list("키워드", 2L, "priceAsc", 1, 10))
                .thenReturn(new PageResponse<>(List.of(), 1, 10, 0L, 0));

        mockMvc.perform(get("/api/v1/products")
                        .param("keyword", "키워드")
                        .param("categoryId", "2")
                        .param("sort", "priceAsc")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());
    }

    // =============================================================
    // GET /api/v1/products/{productId} — 상세
    // =============================================================

    @Test
    @DisplayName("GET /api/v1/products/{id} — 비인증 → 200 (ON_SALE)")
    void getProduct_unauthenticated_returns200() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(1L);
        when(publicProductServiceResponse.detail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 미존재 → 404")
    void getProduct_notFound_returns404() throws Exception {
        when(publicProductServiceResponse.detail(999L))
                .thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(get("/api/v1/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — DRAFT 상품 → 404")
    void getProduct_draftProduct_returns404() throws Exception {
        when(publicProductServiceResponse.detail(100L))
                .thenThrow(new ProductNotFoundException(100L));

        mockMvc.perform(get("/api/v1/products/100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — HIDDEN 상품 → 404")
    void getProduct_hiddenProduct_returns404() throws Exception {
        when(publicProductServiceResponse.detail(200L))
                .thenThrow(new ProductNotFoundException(200L));

        mockMvc.perform(get("/api/v1/products/200"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 상세 응답에 images/options/variants 포함")
    void getProduct_responseContainsImagesOptionsVariants() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(1L);
        when(publicProductServiceResponse.detail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1L))
                .andExpect(jsonPath("$.displayPrice").value(10000))
                .andExpect(jsonPath("$.soldOut").value(false))
                .andExpect(jsonPath("$.images").isArray())
                .andExpect(jsonPath("$.options").isArray())
                .andExpect(jsonPath("$.variants").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 상세 응답에 ownerId/storageKey/basePrice/sku 미포함")
    void getProduct_doesNotContainSensitiveFields() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(1L);
        when(publicProductServiceResponse.detail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.basePrice").doesNotExist())
                .andExpect(jsonPath("$.sku").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — CONSUMER 인증 → 200")
    void getProduct_consumer_returns200() throws Exception {
        PublicProductDetailResponse detail = sampleDetailResponse(1L);
        when(publicProductServiceResponse.detail(1L)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/products/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private PublicProductDetailResponse sampleDetailResponse(long productId) {
        PublicProductImageResponse image = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/img.jpg", 0, true);
        PublicProductOptionResponse option = new PublicProductOptionResponse(
                50L, "색상", List.of());
        PublicProductVariantResponse variant = new PublicProductVariantResponse(
                300L, new BigDecimal("10000"), List.of(), "", true);
        return new PublicProductDetailResponse(
                productId, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(image), List.of(option), List.of(variant)
        );
    }
}
