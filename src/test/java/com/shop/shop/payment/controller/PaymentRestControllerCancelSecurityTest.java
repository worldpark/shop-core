package com.shop.shop.payment.controller;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.dto.OrderCancelResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentCancellationRestController + SecurityConfig REST 체인 MockMvc 보안 테스트 (018).
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/orders/{id}/cancel — 비인증 → 401</li>
 *   <li>POST /api/v1/orders/{id}/cancel — ROLE 없는 인증 → 403</li>
 *   <li>POST /api/v1/orders/{id}/cancel — CONSUMER → 200</li>
 *   <li>POST /api/v1/orders/{id}/cancel — SELLER → 200 (역할 계층 함의)</li>
 *   <li>POST /api/v1/orders/{id}/cancel — ADMIN → 200 (역할 계층 함의)</li>
 *   <li>OrderCancellationConflictException(이행단계) → 409 + ErrorResponse</li>
 *   <li>OrderNotFoundException(타인/미존재) → 404 + ErrorResponse</li>
 *   <li>응답 본문에 userId/ownerId 미포함 (내부정보 비노출)</li>
 *   <li>200 응답 구조: orderId·orderNumber·orderStatus·refunded·refundedAmount·currency 포함</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PaymentRestControllerCancelSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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

    // ============================================================
    // 인증/역할별 접근 제어
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — 비인증 → 401")
    void cancel_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — ROLE 없는 인증 → 403")
    void cancel_noRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + noRoleToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — CONSUMER → 200")
    void cancel_consumer_returns200() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(1L, "cancelled", false, 0L));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — SELLER → 200 (역할 계층 함의)")
    void cancel_seller_returns200() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(1L, "cancelled", false, 0L));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — ADMIN → 200 (역할 계층 함의)")
    void cancel_admin_returns200() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(1L, "cancelled", false, 0L));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ============================================================
    // 예외 매핑
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — 이행단계(OrderCancellationConflictException) → 409")
    void cancel_fulfillmentStage_returns409() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenThrow(new OrderCancellationConflictException(
                        "주문 상태(preparing)에서 취소할 수 없습니다."));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/orders/1/cancel — 타인/미존재 주문(OrderNotFoundException) → 404")
    void cancel_orderNotFound_returns404() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // 응답 구조 + 내부정보 비노출
    // ============================================================

    @Test
    @DisplayName("200 응답 구조: orderId·orderNumber·orderStatus·refunded·refundedAmount·currency 포함")
    void cancel_200_responseStructure() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(42L, "cancelled", false, 0L));

        mockMvc.perform(post("/api/v1/orders/42/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(42L))
                .andExpect(jsonPath("$.orderStatus").value("cancelled"))
                .andExpect(jsonPath("$.refunded").value(false))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.currency").value("KRW"));
    }

    @Test
    @DisplayName("200 응답 구조(환불): refunded=true, refundedAmount > 0")
    void cancel_200_refundedResponseStructure() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(99L, "refunded", true, 20000L));

        mockMvc.perform(post("/api/v1/orders/99/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(99L))
                .andExpect(jsonPath("$.orderStatus").value("refunded"))
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.refundedAmount").value(20000));
    }

    @Test
    @DisplayName("응답 본문에 userId/ownerId 미포함 (내부정보 비노출)")
    void cancel_responseDoesNotContainInternalIds() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenReturn(buildCancelResponse(1L, "cancelled", false, 0L));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    @DisplayName("409 응답에 내부 stacktrace/클래스명 미노출")
    void cancel_409_noInternalInfoLeakage() throws Exception {
        when(paymentServiceResponse.cancel(any(), anyLong()))
                .thenThrow(new OrderCancellationConflictException("이행단계 취소 불가"));

        mockMvc.perform(post("/api/v1/orders/1/cancel")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderCancelResponse buildCancelResponse(long orderId, String orderStatus,
                                                    boolean refunded, long refundedAmount) {
        return new OrderCancelResponse(
                orderId,
                "ORD-018-" + orderId,
                orderStatus,
                refunded,
                refundedAmount,
                "KRW"
        );
    }
}
