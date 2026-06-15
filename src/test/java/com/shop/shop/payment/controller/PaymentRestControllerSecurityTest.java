package com.shop.shop.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.PaymentAmountMismatchException;
import com.shop.shop.common.exception.PaymentEventResolutionException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.dto.PaymentRequest;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.service.PaymentServiceResponse;
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
 * PaymentRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/orders/{id}/payment: CONSUMER 200, 비인증 401, ROLE 없는 인증 403</li>
 *   <li>SELLER/ADMIN → 200 (역할 계층 함의: SELLER > CONSUMER, ADMIN > SELLER)</li>
 *   <li>400/409/404 에러 코드 매핑</li>
 *   <li>GET /api/v1/orders/{id}/payment: 상태 조회 200</li>
 *   <li>타인 주문 GET → 404</li>
 *   <li>상태 조회 시 409(PaymentEventResolutionException) 미발생 (#3)</li>
 *   <li>응답 본문에 ownerId/Entity 미포함</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PaymentRestControllerSecurityTest {

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
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentServiceResponse paymentServiceResponse;
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
    // POST /api/v1/orders/{id}/payment — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 비인증 → 401")
    void pay_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — ROLE 없는 인증 → 403")
    void pay_noRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + noRoleToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — CONSUMER → 200")
    void pay_consumer_returns200() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenReturn(buildPaymentResponse(1L, "paid"));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — SELLER → 200 (역할 계층 함의)")
    void pay_seller_returns200() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenReturn(buildPaymentResponse(1L, "paid"));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — ADMIN → 200 (역할 계층 함의)")
    void pay_admin_returns200() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenReturn(buildPaymentResponse(1L, "paid"));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // =========================================================
    // POST /api/v1/orders/{id}/payment — 예외 매핑
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 주문 미존재(타인/미존재) → 404")
    void pay_orderNotFound_returns404() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 금액 불일치(PaymentAmountMismatchException) → 400")
    void pay_amountMismatch_returns400() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentAmountMismatchException());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 주문 상태 충돌(OrderConfirmationConflictException) → 409")
    void pay_orderConflict_returns409() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new OrderConfirmationConflictException());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 이벤트 완결성 실패(PaymentEventResolutionException) → 409")
    void pay_resolutionFailure_returns409() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentEventResolutionException());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/payment — 결제 진행 중(PaymentInProgressException) → 409")
    void pay_inProgress_returns409() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentInProgressException());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // =========================================================
    // GET /api/v1/orders/{id}/payment — 상태 조회
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/orders/1/payment — CONSUMER 200")
    void getPaymentStatus_consumer_returns200() throws Exception {
        when(paymentServiceResponse.getPaymentStatus(any(), anyLong()))
                .thenReturn(buildPaymentResponse(1L, "paid"));

        mockMvc.perform(get("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/orders/1/payment — 비인증 → 401")
    void getPaymentStatus_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/1/payment"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/orders/999/payment — 타인 주문 → 404")
    void getPaymentStatus_foreignOrder_returns404() throws Exception {
        when(paymentServiceResponse.getPaymentStatus(any(), anyLong()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/api/v1/orders/999/payment")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/orders/1/payment — 이벤트 완결성 실패 주문이어도 상태 조회는 409 미발생 (#3)")
    void getPaymentStatus_noResolutionException_returns200() throws Exception {
        // 상태 조회 경로는 getOrderSnapshot 사용 → 이벤트 완결성 검증 없음
        // PaymentEventResolutionException이 발생하지 않음을 Mock으로 표현
        when(paymentServiceResponse.getPaymentStatus(any(), anyLong()))
                .thenReturn(buildPaymentResponse(1L, "pending"));

        mockMvc.perform(get("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    // =========================================================
    // 응답 필드 비노출
    // =========================================================

    @Test
    @DisplayName("POST 응답에 ownerId/userId 미포함")
    void pay_responseDoesNotContainOwnerId() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenReturn(buildPaymentResponse(1L, "paid"));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private PaymentResponse buildPaymentResponse(long paymentId, String status) {
        return new PaymentResponse(
                paymentId, 1L, "ORD-20260608-001",
                status, "mock",
                BigDecimal.valueOf(10000),
                "MOCK-TX-001", Instant.now()
        );
    }
}
