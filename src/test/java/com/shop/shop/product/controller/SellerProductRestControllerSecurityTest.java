package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductCreateRequest;
import com.shop.shop.product.dto.ProductUpdateRequest;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerProductRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - POST /api/v1/seller/products: SELLER 200·ADMIN 200·CONSUMER 403·비인증 401, basePrice 음수 400
 * - PATCH /api/v1/seller/products/{id}: 소유자 200·타 판매자 404·ADMIN 200·미존재 404
 * - 응답에 Entity 필드 미노출 단언
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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

    private String adminToken;
    private String sellerToken;
    private String sellerToken2;
    private String consumerToken;

    private static final long SELLER_ID = 2L;
    private static final long SELLER2_ID = 5L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(ADMIN_ID, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(SELLER_ID, "seller@example.com", List.of("ROLE_SELLER"));
        sellerToken2 = jwtTokenProvider.createAccess(SELLER2_ID, "seller2@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 stub: CategoryRepository (목록 조회)
        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    // ============================================================
    // POST /api/v1/seller/products
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/seller/products — SELLER → 200 ProductResponse(status=DRAFT)")
    void register_seller_returns_200_with_draft_status() throws Exception {
        Product saved = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.save(any())).thenReturn(saved);

        ProductCreateRequest req = new ProductCreateRequest(null, "상품A", "설명", new BigDecimal("10000"));
        String body = mockMvc.perform(post("/api/v1/seller/products")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.ownerId").value(SELLER_ID))
                .andReturn().getResponse().getContentAsString();

        // Entity 미노출 단언
        assertFieldAbsent(body, "category");
    }

    @Test
    @DisplayName("POST /api/v1/seller/products — ADMIN → 200(RoleHierarchy 함의)")
    void register_admin_returns_200() throws Exception {
        Product saved = sampleProduct(ADMIN_ID, PRODUCT_ID);
        when(productRepository.save(any())).thenReturn(saved);

        ProductCreateRequest req = new ProductCreateRequest(null, "상품B", null, new BigDecimal("5000"));
        mockMvc.perform(post("/api/v1/seller/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/seller/products — CONSUMER → 403")
    void register_consumer_returns_403() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(null, "상품C", null, new BigDecimal("1000"));
        mockMvc.perform(post("/api/v1/seller/products")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST /api/v1/seller/products — 비인증 → 401")
    void register_unauthenticated_returns_401() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(null, "상품D", null, new BigDecimal("1000"));
        mockMvc.perform(post("/api/v1/seller/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/v1/seller/products — basePrice 음수 → 400")
    void register_negative_base_price_returns_400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(null, "상품E", null, new BigDecimal("-1"));
        mockMvc.perform(post("/api/v1/seller/products")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ============================================================
    // PATCH /api/v1/seller/products/{id}
    // ============================================================

    @Test
    @DisplayName("PATCH /api/v1/seller/products/{id} — 소유자(SELLER) → 200")
    void update_owner_seller_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductUpdateRequest req = new ProductUpdateRequest(null, "수정상품", null,
                new BigDecimal("20000"), ProductStatus.ON_SALE);
        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/seller/products/{id} — 타 판매자 → 404(존재 은닉)")
    void update_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID); // 소유자는 SELLER_ID
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        // sellerToken2: SELLER2_ID (타 판매자)
        ProductUpdateRequest req = new ProductUpdateRequest(null, "타인수정", null,
                new BigDecimal("1000"), ProductStatus.DRAFT);
        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID)
                        .header("Authorization", "Bearer " + sellerToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH /api/v1/seller/products/{id} — ADMIN → 200(전체 수정)")
    void update_admin_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductUpdateRequest req = new ProductUpdateRequest(null, "ADMIN수정", null,
                new BigDecimal("5000"), ProductStatus.HIDDEN);
        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/seller/products/{id} — 미존재 → 404")
    void update_not_found_returns_404() throws Exception {
        when(productRepository.findById(9999L)).thenReturn(Optional.empty());

        ProductUpdateRequest req = new ProductUpdateRequest(null, "수정", null,
                new BigDecimal("1000"), ProductStatus.DRAFT);
        mockMvc.perform(patch("/api/v1/seller/products/9999")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product sampleProduct(long ownerId, long productId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("10000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, productId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }

    private void assertFieldAbsent(String jsonBody, String fieldName) {
        assertThat(jsonBody)
                .as("응답 본문에 Entity 필드 '%s'가 포함되어선 안 됩니다", fieldName)
                .doesNotContain("\"" + fieldName + "\":");
    }
}
