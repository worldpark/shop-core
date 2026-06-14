package com.shop.shop.cart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.dto.CartItemAddRequest;
import com.shop.shop.cart.dto.CartItemQuantityUpdateRequest;
import com.shop.shop.cart.dto.CartItemResponse;
import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.service.CartServiceResponse;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.common.exception.CartItemNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CartRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /api/v1/cart: CONSUMER 200, 비인증 401, ROLE 없는 인증 403, SELLER/ADMIN 200</li>
 *   <li>POST /api/v1/cart/items: 성공 200, 검증 실패(quantity<1/variantId null) 400</li>
 *   <li>PATCH /api/v1/cart/items/{id}: 성공 200, 타인/미존재 404</li>
 *   <li>DELETE /api/v1/cart/items/{id}: 성공 204, 타인/미존재 404</li>
 *   <li>응답에 stock 수치/ownerId/Entity 미포함</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class CartRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

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
    private CartServiceResponse cartServiceResponse;

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


    private String adminToken;
    private String sellerToken;
    private String consumerToken;
    private String noRoleToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
        noRoleToken = jwtTokenProvider.createAccess(4L, "norole@example.com", List.of());

        CartResponse emptyCart = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartServiceResponse.getCart(any())).thenReturn(emptyCart);
        when(cartServiceResponse.addItem(any(), any())).thenReturn(emptyCart);
        when(cartServiceResponse.updateQuantity(any(), anyLong(), any())).thenReturn(emptyCart);
    }

    // =============================================================
    // GET /api/v1/cart — 인증/역할별 접근
    // =============================================================

    @Test
    @DisplayName("GET /api/v1/cart — 비인증 → 401")
    void getCart_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/cart — CONSUMER → 200")
    void getCart_consumer_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/cart — SELLER → 200 (역할 계층 함의)")
    void getCart_seller_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/cart — ADMIN → 200 (역할 계층 함의)")
    void getCart_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/cart — ROLE 없는 인증 토큰 → 403")
    void getCart_noRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer " + noRoleToken))
                .andExpect(status().isForbidden());
    }

    // =============================================================
    // POST /api/v1/cart/items
    // =============================================================

    @Test
    @DisplayName("POST /api/v1/cart/items — 성공 200")
    void addItem_success_returns200() throws Exception {
        CartItemAddRequest request = new CartItemAddRequest(10L, 2);

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/cart/items — variantId null → 400")
    void addItem_variantIdNull_returns400() throws Exception {
        String requestJson = "{\"variantId\":null,\"quantity\":1}";

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/cart/items — quantity=0 → 400")
    void addItem_quantityZero_returns400() throws Exception {
        String requestJson = "{\"variantId\":10,\"quantity\":0}";

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/cart/items — 비인증 → 401")
    void addItem_unauthenticated_returns401() throws Exception {
        CartItemAddRequest request = new CartItemAddRequest(10L, 2);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // =============================================================
    // PATCH /api/v1/cart/items/{id}
    // =============================================================

    @Test
    @DisplayName("PATCH /api/v1/cart/items/{id} — 성공 200")
    void updateQuantity_success_returns200() throws Exception {
        CartItemQuantityUpdateRequest request = new CartItemQuantityUpdateRequest(3);

        mockMvc.perform(patch("/api/v1/cart/items/1")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/cart/items/{id} — 타인/미존재 → 404 존재 은닉")
    void updateQuantity_otherUserItem_returns404() throws Exception {
        CartItemQuantityUpdateRequest request = new CartItemQuantityUpdateRequest(3);
        when(cartServiceResponse.updateQuantity(any(), anyLong(), any()))
                .thenThrow(new CartItemNotFoundException());

        mockMvc.perform(patch("/api/v1/cart/items/999")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =============================================================
    // DELETE /api/v1/cart/items/{id}
    // =============================================================

    @Test
    @DisplayName("DELETE /api/v1/cart/items/{id} — 성공 204")
    void removeItem_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/cart/items/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/cart/items/{id} — 타인/미존재 → 404 존재 은닉")
    void removeItem_otherUserItem_returns404() throws Exception {
        doThrow(new CartItemNotFoundException())
                .when(cartServiceResponse).removeItem(any(), anyLong());

        mockMvc.perform(delete("/api/v1/cart/items/999")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =============================================================
    // 응답 필드 비노출 검증
    // =============================================================

    @Test
    @DisplayName("GET /api/v1/cart 응답에 stock 수치/ownerId 미포함")
    void getCart_responseDoesNotContainSensitiveFields() throws Exception {
        CartItemResponse itemResponse = new CartItemResponse(
                1L, 10L, 100L, "테스트 상품", "빨강", null,
                new BigDecimal("1000"), 2, new BigDecimal("2000"), true, true);
        CartResponse cartResponse = new CartResponse(1L, List.of(itemResponse), 2,
                new BigDecimal("2000"), false);
        when(cartServiceResponse.getCart(any())).thenReturn(cartResponse);

        mockMvc.perform(get("/api/v1/cart")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stock").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockEnough").exists())
                .andExpect(jsonPath("$.items[0].available").exists());
    }
}
