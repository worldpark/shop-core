package com.shop.shop.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.ProductNotPurchasableForOrderException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.dto.ShippingAddressResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.order.service.OrderServiceResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import com.shop.shop.common.dto.PageResponse;
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
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/orders: CONSUMER 201, 비인증 401, ROLE 없는 인증 403, SELLER/ADMIN 201(역할 계층)</li>
 *   <li>POST /api/v1/orders: 배송지 검증 실패 400 / 재고 부족 409 / 구매 불가 409</li>
 *   <li>GET /api/v1/orders: 자기 목록 200</li>
 *   <li>GET /api/v1/orders/{id}: 자기 상세 200, 타인 404</li>
 *   <li>응답에 ownerId/Entity/로컬 절대경로 미포함</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class OrderRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    // Repository mocks (DB 자동설정 제외 환경)
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
    private OrderRepository orderRepository;
    @MockitoBean
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;
    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private OrderServiceResponse orderServiceResponse;
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
    private String noRoleToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
        noRoleToken = jwtTokenProvider.createAccess(4L, "norole@example.com", List.of());
    }

    // =========================================================
    // POST /api/v1/orders — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/orders — 비인증 → 401")
    void createOrder_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/orders — ROLE 없는 인증 → 403")
    void createOrder_noRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + noRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/orders — CONSUMER → 201")
    void createOrder_consumer_returns201() throws Exception {
        when(orderServiceResponse.createOrder(any(), any())).thenReturn(buildOrderResponse(1L));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/orders — SELLER → 201 (역할 계층 함의)")
    void createOrder_seller_returns201() throws Exception {
        when(orderServiceResponse.createOrder(any(), any())).thenReturn(buildOrderResponse(1L));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/orders — ADMIN → 201 (역할 계층 함의)")
    void createOrder_admin_returns201() throws Exception {
        when(orderServiceResponse.createOrder(any(), any())).thenReturn(buildOrderResponse(1L));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated());
    }

    // =========================================================
    // POST /api/v1/orders — 검증/예외
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/orders — recipient 누락 → 400")
    void createOrder_missingRecipient_returns400() throws Exception {
        String invalidJson = "{\"phone\":\"010\",\"postcode\":\"12345\",\"address1\":\"서울\"}";

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/orders — 재고 부족(InsufficientStockException) → 409")
    void createOrder_insufficientStock_returns409() throws Exception {
        when(orderServiceResponse.createOrder(any(), any()))
                .thenThrow(new InsufficientStockException());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/orders — 구매 불가(ProductNotPurchasableForOrderException) → 409")
    void createOrder_notPurchasable_returns409() throws Exception {
        when(orderServiceResponse.createOrder(any(), any()))
                .thenThrow(new ProductNotPurchasableForOrderException());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // =========================================================
    // GET /api/v1/orders
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/orders — CONSUMER 200")
    void getMyOrders_consumer_returns200() throws Exception {
        when(orderServiceResponse.getMyOrders(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0));

        mockMvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/orders — 비인증 → 401")
    void getMyOrders_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================
    // GET /api/v1/orders/{id}
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/orders/{id} — CONSUMER 200")
    void getMyOrder_consumer_returns200() throws Exception {
        when(orderServiceResponse.getMyOrder(any(), anyLong())).thenReturn(buildOrderResponse(1L));

        mockMvc.perform(get("/api/v1/orders/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} — 타인/미존재 → 404 존재 은닉")
    void getMyOrder_notOwned_returns404() throws Exception {
        when(orderServiceResponse.getMyOrder(any(), anyLong()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/api/v1/orders/999")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================
    // 응답 필드 비노출
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/orders/{id} 응답에 ownerId(userId) 미포함")
    void getMyOrder_responseDoesNotContainOwnerId() throws Exception {
        when(orderServiceResponse.getMyOrder(any(), anyLong())).thenReturn(buildOrderResponse(1L));

        mockMvc.perform(get("/api/v1/orders/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private String validRequestJson() throws Exception {
        return objectMapper.writeValueAsString(
                new OrderCreateRequest("홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호", null)
        );
    }

    private OrderResponse buildOrderResponse(long orderId) {
        return new OrderResponse(
                orderId, "ORD-20260101-000000-ABCD1234", "pending",
                List.of(),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000"),
                new ShippingAddressResponse("홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호"),
                Instant.now(),
                List.of()
        );
    }
}
