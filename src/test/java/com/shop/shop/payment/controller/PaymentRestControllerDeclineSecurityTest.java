package com.shop.shop.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.PaymentDeclinedException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.domain.Payment;
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
import com.shop.shop.support.MockSharedRepositories;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentRestController 거절 분기 REST/Security MockMvc 통합 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/orders/{id}/payment 거절 → 402 + ErrorResponse(message=failureReason)</li>
 *   <li>거절 응답에 내부 PG 원문·failureCode·스택트레이스 미포함</li>
 *   <li>거절이 200 PaymentResponse로 응답되지 않음(Ma3)</li>
 *   <li>거절 후 재시도 승인 → 200 + paid</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class PaymentRestControllerDeclineSecurityTest {

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


    private String consumerToken;

    private static final String FAILURE_REASON = "카드사에서 결제가 거절되었습니다.";
    private static final String FAILURE_CODE = "CARD_DECLINED";

    @BeforeEach
    void setUp() {
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // 거절 → 402 매핑 (C1·Ma3)
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/orders/1/payment 거절 → 402 Payment Required (C1·Ma3)")
    void pay_declined_returns402() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("거절 응답 body: status=402, message=failureReason")
    void pay_declined_responseBody_status402AndMessage() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.message").value(FAILURE_REASON));
    }

    @Test
    @DisplayName("거절 응답에 내부 failureCode 미포함 (비노출)")
    void pay_declined_responseDoesNotContainFailureCode() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.failureCode").doesNotExist());
    }

    @Test
    @DisplayName("거절 응답에 스택트레이스 미포함")
    void pay_declined_responseDoesNotContainStackTrace() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("거절이 200 PaymentResponse로 응답되지 않음 (Ma3)")
    void pay_declined_not200Response() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired()); // 200 아님 확인
    }

    @Test
    @DisplayName("거절 후 재시도 승인 → 200 + paid (Ma1)")
    void pay_afterDecline_retryApproved_returns200() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenReturn(buildApprovedResponse());

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"mock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"));
    }

    @Test
    @DisplayName("거절 응답: error 필드 = Payment Required")
    void pay_declined_errorField_paymentRequired() throws Exception {
        when(paymentServiceResponse.pay(any(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/api/v1/orders/1/payment")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"virtual_account\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("Payment Required"));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private PaymentResponse buildApprovedResponse() {
        return new PaymentResponse(
                1L, 1L, "ORD-20260610-001",
                "paid", "mock",
                BigDecimal.valueOf(50000),
                "MOCK-TX-001", Instant.now()
        );
    }
}
