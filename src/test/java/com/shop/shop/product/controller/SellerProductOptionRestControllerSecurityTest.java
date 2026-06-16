package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.exception.DuplicateOptionNameException;
import com.shop.shop.common.exception.DuplicateOptionValueException;
import com.shop.shop.common.exception.OptionNotFoundException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.dto.OptionValueCreateRequest;
import com.shop.shop.product.dto.ProductOptionCreateRequest;
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
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerProductOptionRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - GET/POST /api/v1/seller/products/{id}/options: SELLER 200 / ADMIN 200 / CONSUMER 403 / 비인증 401
 * - POST /api/v1/seller/products/{id}/options: 중복 409, 검증 실패 400, 타 판매자 404
 * - POST /api/v1/seller/products/{id}/options/{optionId}/values: 하위리소스 불일치 404, 중복 409
 * - Entity 미노출 단언
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerProductOptionRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private String sellerToken2;
    private String consumerToken;

    private static final long SELLER_ID = 2L;
    private static final long SELLER2_ID = 5L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long OPTION_ID = 20L;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(ADMIN_ID, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(SELLER_ID, "seller@example.com", List.of("ROLE_SELLER"));
        sellerToken2 = jwtTokenProvider.createAccess(SELLER2_ID, "seller2@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    // ============================================================
    // GET /api/v1/seller/products/{id}/options
    // ============================================================

    @Test
    @DisplayName("GET options — SELLER → 200")
    void listOptions_seller_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET options — ADMIN → 200 (RoleHierarchy 함의)")
    void listOptions_admin_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findByProductIdOrderById(PRODUCT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET options — CONSUMER → 403")
    void listOptions_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET options — 비인증 → 401")
    void listOptions_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET options — 타 판매자 상품 → 404 (존재 은닉)")
    void listOptions_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID); // 소유자는 SELLER_ID
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        // sellerToken2는 SELLER2_ID (타 판매자)
        mockMvc.perform(get("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // POST /api/v1/seller/products/{id}/options
    // ============================================================

    @Test
    @DisplayName("POST options — SELLER 성공 → 200 ProductOptionResponse")
    void createOption_seller_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption savedOption = sampleOption(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "색상")).thenReturn(false);
        when(productOptionRepository.save(any())).thenReturn(savedOption);

        String body = mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductOptionCreateRequest("색상"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionId").value(OPTION_ID))
                .andExpect(jsonPath("$.name").value("색상"))
                .andReturn().getResponse().getContentAsString();

        // Entity 미노출 단언
        assertFieldAbsent(body, "product");
    }

    @Test
    @DisplayName("POST options — ADMIN 성공 → 200")
    void createOption_admin_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption savedOption = sampleOption(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "사이즈")).thenReturn(false);
        when(productOptionRepository.save(any())).thenReturn(savedOption);

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductOptionCreateRequest("사이즈"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST options — V1: 옵션명 중복 → 409")
    void createOption_duplicate_name_returns_409() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.existsByProductIdAndName(PRODUCT_ID, "색상")).thenReturn(true);

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductOptionCreateRequest("색상"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST options — @Valid: name 빈값 → 400")
    void createOption_blank_name_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductOptionCreateRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST options — 타 판매자 → 404")
    void createOption_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options")
                        .header("Authorization", "Bearer " + sellerToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProductOptionCreateRequest("색상"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // POST /api/v1/seller/products/{id}/options/{optionId}/values
    // ============================================================

    @Test
    @DisplayName("POST option values — 성공 → 200 OptionValueResponse")
    void createOptionValue_success_returns_200() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption option = sampleOption(OPTION_ID, product);
        OptionValue savedValue = sampleOptionValue(30L, option);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));
        when(optionValueRepository.existsByOptionIdAndValue(OPTION_ID, "빨강")).thenReturn(false);
        when(optionValueRepository.save(any())).thenReturn(savedValue);

        String body = mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID
                        + "/options/" + OPTION_ID + "/values")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OptionValueCreateRequest("빨강"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionValueId").value(30))
                .andExpect(jsonPath("$.optionId").value(OPTION_ID))
                .andExpect(jsonPath("$.value").value("빨강"))
                .andReturn().getResponse().getContentAsString();

        // Entity 미노출 단언
        assertFieldAbsent(body, "option");
    }

    @Test
    @DisplayName("POST option values — V2: 옵션값 중복 → 409")
    void createOptionValue_duplicate_value_returns_409() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption option = sampleOption(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));
        when(optionValueRepository.existsByOptionIdAndValue(OPTION_ID, "빨강")).thenReturn(true);

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID
                        + "/options/" + OPTION_ID + "/values")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OptionValueCreateRequest("빨강"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST option values — V10: optionId가 다른 상품 소속 → 404")
    void createOptionValue_option_not_in_product_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        // 옵션이 없음 → filter 통과 실패
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/seller/products/" + PRODUCT_ID + "/options/999/values")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OptionValueCreateRequest("빨강"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // DELETE /api/v1/seller/products/{id}/options/{optionId}
    // ============================================================

    @Test
    @DisplayName("DELETE option — SELLER(소유자) → 204")
    void deleteOption_seller_returns_204() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption option = sampleOption(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE option — ADMIN → 204 (RoleHierarchy 함의)")
    void deleteOption_admin_returns_204() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        ProductOption option = sampleOption(OPTION_ID, product);

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option));

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE option — CONSUMER → 403")
    void deleteOption_consumer_returns_403() throws Exception {
        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID)
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("DELETE option — 비인증 → 401")
    void deleteOption_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("DELETE option — 타 판매자 → 404 (존재 은닉)")
    void deleteOption_other_seller_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID); // 소유자는 SELLER_ID
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID)
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("DELETE option — 존재하지 않는 옵션 → 404")
    void deleteOption_not_found_returns_404() throws Exception {
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productOptionRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/seller/products/" + PRODUCT_ID + "/options/999")
                        .header("Authorization", "Bearer " + sellerToken))
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

    private ProductOption sampleOption(long optionId, Product product) {
        ProductOption option = ProductOption.create(product, "색상");
        try {
            var idField = ProductOption.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(option, optionId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return option;
    }

    private OptionValue sampleOptionValue(long valueId, ProductOption option) {
        OptionValue ov = OptionValue.create(option, "빨강");
        try {
            var idField = OptionValue.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(ov, valueId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ov;
    }

    private void assertFieldAbsent(String jsonBody, String fieldName) {
        assertThat(jsonBody)
                .as("응답 본문에 Entity 필드 '%s'가 포함되어선 안 됩니다", fieldName)
                .doesNotContain("\"" + fieldName + "\":");
    }
}
