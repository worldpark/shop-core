package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.exception.DuplicateSkuException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductVariantCreateRequest;
import com.shop.shop.product.dto.ProductVariantUpdateRequest;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerProductVariantRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - GET/POST /api/v1/seller/products/{id}/variants: SELLER 200 / ADMIN 200 / CONSUMER 403 / 비인증 401
 * - POST variants: SKU 중복 409, @Valid 검증 실패 400, 타 판매자 404
 * - PATCH variants/{id}: variant 하위리소스 불일치 404, 성공 200
 * - Entity 미노출 단언
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductVariantRestControllerSecurityTest {

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

    private String adminToken;
    private String sellerToken;
    private String sellerToken2;
    private String consumerToken;

    private static final long SELLER_ID = 2L;
    private static final long SELLER2_ID = 5L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long VARIANT_ID = 50L;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(ADMIN_ID, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(SELLER_ID, "seller@example.com", List.of("ROLE_SELLER"));
        sellerToken2 = jwtTokenProvider.createAccess(SELLER2_ID, "seller2@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    // ============================================================
    // GET /api/v1/seller/products/{id}/variants
    // ============================================================

    @Test
    @DisplayName("GET variants — SELLER → 200")
    void listVariants_seller_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET variants — ADMIN → 200 (RoleHierarchy 함의)")
    void listVariants_admin_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET variants — CONSUMER → 403")
    void listVariants_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET variants — 비인증 → 401")
    void listVariants_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/variants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET variants — 타 판매자 상품 → 404 (존재 은닉)")
    void listVariants_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // POST /api/v1/seller/products/{id}/variants
    // ============================================================

    @Test
    @DisplayName("POST variants — SELLER 성공(옵션 없는 상품) → 200 ProductVariantResponse")
    void createVariant_seller_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductVariant savedVariant = sampleVariant(VARIANT_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-001")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.save(any())).thenReturn(savedVariant);

        ProductVariantCreateRequest req = new ProductVariantCreateRequest(
                "SKU-001", new BigDecimal("10000"), 5, true, List.of());

        String body = mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(VARIANT_ID))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andReturn().getResponse().getContentAsString();

        // Entity 미노출 단언 (product 직접 노출 없음)
        assertFieldAbsent(body, "product");
    }

    @Test
    @DisplayName("POST variants — ADMIN 성공 → 200")
    void createVariant_admin_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductVariant savedVariant = sampleVariant(VARIANT_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-ADM")).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.save(any())).thenReturn(savedVariant);

        ProductVariantCreateRequest req = new ProductVariantCreateRequest(
                "SKU-ADM", new BigDecimal("5000"), 3, false, List.of());

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST variants — V3: SKU 중복 → 409")
    void createVariant_duplicate_sku_returns_409() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.existsBySku("SKU-DUP")).thenReturn(true);

        ProductVariantCreateRequest req = new ProductVariantCreateRequest(
                "SKU-DUP", new BigDecimal("10000"), 5, true, List.of());

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST variants — @Valid: sku 빈값 → 400")
    void createVariant_blank_sku_returns_400() throws Exception {
        ProductVariantCreateRequest req = new ProductVariantCreateRequest(
                "", new BigDecimal("10000"), 5, true, List.of());

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST variants — @Valid: price null → 400")
    void createVariant_null_price_returns_400() throws Exception {
        String body = "{\"sku\":\"SKU-X\",\"price\":null,\"stock\":5,\"active\":true,\"optionValueIds\":[]}";

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST variants — 타 판매자 → 404")
    void createVariant_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductVariantCreateRequest req = new ProductVariantCreateRequest(
                "SKU-X", new BigDecimal("10000"), 5, true, List.of());

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/variants")
                        .header("Authorization", "Bearer " + sellerToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // PATCH /api/v1/seller/products/{id}/variants/{variantId}
    // ============================================================

    @Test
    @DisplayName("PATCH variant — 소유자 성공 → 200 ProductVariantResponse")
    void updateVariant_owner_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductVariant variant = sampleVariant(VARIANT_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));
        when(productVariantRepository.existsBySkuAndIdNot("SKU-UPD", VARIANT_ID)).thenReturn(false);
        when(optionValueRepository.findByOption_ProductId(PRODUCT_ID)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(variant));

        ProductVariantUpdateRequest req = new ProductVariantUpdateRequest(
                "SKU-UPD", new BigDecimal("20000"), 10, false, List.of());

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/variants/" + VARIANT_ID)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(VARIANT_ID));
    }

    @Test
    @DisplayName("PATCH variant — V11: 다른 상품 variant → 404")
    void updateVariant_variant_not_in_product_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        // variant가 없으면 VariantNotFoundException
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productVariantRepository.findById(999L)).thenReturn(Optional.empty());

        ProductVariantUpdateRequest req = new ProductVariantUpdateRequest(
                "SKU-UPD", new BigDecimal("20000"), 10, false, List.of());

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/variants/999")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH variant — 타 판매자 → 404")
    void updateVariant_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductVariantUpdateRequest req = new ProductVariantUpdateRequest(
                "SKU-UPD", new BigDecimal("20000"), 10, false, List.of());

        mockMvc.perform(patch("/api/v1/seller/products/" + PRODUCT_ID + "/variants/" + VARIANT_ID)
                        .header("Authorization", "Bearer " + sellerToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
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

    private ProductVariant sampleVariant(long variantId, Product product) {
        ProductVariant variant = ProductVariant.create(product, "SKU-001",
                new BigDecimal("10000"), 5, true, new HashSet<>());
        try {
            var idField = ProductVariant.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(variant, variantId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return variant;
    }

    private void assertFieldAbsent(String jsonBody, String fieldName) {
        assertThat(jsonBody)
                .as("응답 본문에 Entity 필드 '%s'가 포함되어선 안 됩니다", fieldName)
                .doesNotContain("\"" + fieldName + "\":");
    }
}
