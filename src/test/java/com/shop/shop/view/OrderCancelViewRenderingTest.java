package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.OrderItemOptionValueResponse;
import com.shop.shop.order.dto.OrderItemResponse;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.ShippingAddressResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.payment.dto.OrderCancelResponse;
import com.shop.shop.payment.dto.PaymentStatusView;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 취소 화면(POST /orders/{id}/cancel + templates/order/detail.html 취소 영역)
 * Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증.
 * PaymentFacade·OrderFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 * 기존 016/017 View 테스트(PaymentViewRenderingTest 등)의 MockMvc 설정·시큐리티·모델 stubbing 패턴 재사용.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(C1) POST /orders/{id}/cancel 취소 성공 → redirect:/orders/{id} + flashSuccess</li>
 *   <li>(C2) POST /orders/{id}/cancel 취소 불가(OrderCancellationConflictException 409) → flashError + redirect</li>
 *   <li>(C3) 비인증 POST /orders/{id}/cancel → 302 /login redirect</li>
 *   <li>(C4) GET /orders/{id} — pending 주문에서 취소 버튼 노출</li>
 *   <li>(C5) GET /orders/{id} — paid 주문에서 취소 버튼 노출</li>
 *   <li>(C6) GET /orders/{id} — cancelled 주문에서 취소 버튼 미노출 + '취소됨' 상태 표시</li>
 *   <li>(C7) GET /orders/{id} — refunded 주문에서 취소 버튼 미노출 + '환불됨' 상태 표시</li>
 *   <li>(C8) GET /orders/{id} — preparing 주문에서 취소 버튼 미노출</li>
 *   <li>(C9) GET /orders/{id} — shipping 주문에서 취소 버튼 미노출</li>
 *   <li>(C10) GET /orders/{id} — delivered 주문에서 취소 버튼 미노출</li>
 *   <li>(C11) GET /orders/{id} — cancelled 주문에서 결제 폼 미노출</li>
 *   <li>(C12) GET /orders/{id} — refunded 주문에서 결제 폼 미노출</li>
 * </ul>
 *
 * <p>인가: SecurityConfig View 체인 /orders/[orderId]/cancel hasRole("CONSUMER").
 * 미인증 시 302 /login (컨트롤러 도달 전 Security 처리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class OrderCancelViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    // ============================================================
    // MockitoBean — 기존 view 테스트 패턴 준수 (PaymentViewRenderingTest 재사용)
    // ============================================================

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
    private OrderFacade orderFacade;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @BeforeEach
    void setUp() {
        // 기본값: pending 주문 + 결제 대기 상태
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(pendingOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(payableStatusView());
    }

    // ============================================================
    // (C1) POST /orders/{id}/cancel — 취소 성공
    // ============================================================

    @Test
    @DisplayName("(C1) POST /orders/{id}/cancel — 취소 성공 → redirect:/orders/{id} + flashSuccess")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void cancel_success_redirectWithFlashSuccess() throws Exception {
        when(paymentFacade.cancel(anyString(), anyLong()))
                .thenReturn(cancelledResponse());

        mockMvc.perform(post("/orders/1/cancel")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attribute("flashSuccess", "주문이 취소되었습니다."));
    }

    // ============================================================
    // (C2) POST /orders/{id}/cancel — 취소 불가(409)
    // ============================================================

    @Test
    @DisplayName("(C2) POST /orders/{id}/cancel — 취소 불가(OrderCancellationConflictException 409) → flashError + redirect")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void cancel_conflictException_flashErrorAndRedirect() throws Exception {
        when(paymentFacade.cancel(anyString(), anyLong()))
                .thenThrow(new OrderCancellationConflictException("이행 중인 주문은 취소할 수 없습니다."));

        mockMvc.perform(post("/orders/1/cancel")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (C3) 비인증 POST /orders/{id}/cancel → 302 /login
    // ============================================================

    @Test
    @DisplayName("(C3) 비인증 POST /orders/{id}/cancel → 302 /login redirect")
    void cancel_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/orders/1/cancel")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // (C4) GET /orders/{id} — pending 주문: 취소 버튼 노출
    // ============================================================

    @Test
    @DisplayName("(C4) GET /orders/{id} — pending 주문에서 취소 버튼 노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_pendingOrder_showsCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(pendingOrderResponse());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 폼 action이 /orders/1/cancel이어야 함")
                .contains("/orders/1/cancel");
        assertThat(body).as("주문 취소 버튼이 렌더링되어야 함")
                .contains("주문 취소");
    }

    // ============================================================
    // (C5) GET /orders/{id} — paid 주문: 취소 버튼 노출
    // ============================================================

    @Test
    @DisplayName("(C5) GET /orders/{id} — paid 주문에서 취소 버튼 노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_paidOrder_showsCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(paidOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 폼 action이 /orders/1/cancel이어야 함")
                .contains("/orders/1/cancel");
        assertThat(body).as("주문 취소 버튼이 렌더링되어야 함")
                .contains("주문 취소");
    }

    // ============================================================
    // (C6) GET /orders/{id} — cancelled 주문: 취소 버튼 미노출 + '취소됨' 상태 표시
    // ============================================================

    @Test
    @DisplayName("(C6) GET /orders/{id} — cancelled 주문에서 취소 버튼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_cancelledOrder_doesNotShowCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(cancelledOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(cancelledStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 버튼이 없어야 함")
                .doesNotContain("주문 취소");
    }

    @Test
    @DisplayName("(C6) GET /orders/{id} — cancelled 주문에서 '취소됨' 상태 배지 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_cancelledOrder_showsCancelledStatusBadge() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(cancelledOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(cancelledStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("'취소됨' 상태 텍스트가 렌더링되어야 함")
                .contains("취소됨");
    }

    // ============================================================
    // (C7) GET /orders/{id} — refunded 주문: 취소 버튼 미노출 + '환불됨' 상태 표시
    // ============================================================

    @Test
    @DisplayName("(C7) GET /orders/{id} — refunded 주문에서 취소 버튼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_refundedOrder_doesNotShowCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(refundedOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(refundedStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 버튼이 없어야 함")
                .doesNotContain("주문 취소");
    }

    @Test
    @DisplayName("(C7) GET /orders/{id} — refunded 주문에서 '환불됨' 상태 배지 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_refundedOrder_showsRefundedStatusBadge() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(refundedOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(refundedStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("'환불됨' 상태 텍스트가 렌더링되어야 함")
                .contains("환불됨");
    }

    // ============================================================
    // (C8) GET /orders/{id} — preparing 주문: 취소 버튼 미노출
    // ============================================================

    @Test
    @DisplayName("(C8) GET /orders/{id} — preparing 주문에서 취소 버튼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_preparingOrder_doesNotShowCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(preparingOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 버튼이 없어야 함")
                .doesNotContain("주문 취소");
    }

    // ============================================================
    // (C9) GET /orders/{id} — shipping 주문: 취소 버튼 미노출
    // ============================================================

    @Test
    @DisplayName("(C9) GET /orders/{id} — shipping 주문에서 취소 버튼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_shippingOrder_doesNotShowCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(shippingOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 버튼이 없어야 함")
                .doesNotContain("주문 취소");
    }

    // ============================================================
    // (C10) GET /orders/{id} — delivered 주문: 취소 버튼 미노출
    // ============================================================

    @Test
    @DisplayName("(C10) GET /orders/{id} — delivered 주문에서 취소 버튼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_deliveredOrder_doesNotShowCancelButton() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(deliveredOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("취소 버튼이 없어야 함")
                .doesNotContain("주문 취소");
    }

    // ============================================================
    // (C11) GET /orders/{id} — cancelled 주문: 결제 폼 미노출
    // ============================================================

    @Test
    @DisplayName("(C11) GET /orders/{id} — cancelled 주문에서 결제 폼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_cancelledOrder_doesNotShowPaymentForm() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(cancelledOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(cancelledStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제하기 버튼이 없어야 함")
                .doesNotContain("결제하기");
    }

    // ============================================================
    // (C12) GET /orders/{id} — refunded 주문: 결제 폼 미노출
    // ============================================================

    @Test
    @DisplayName("(C12) GET /orders/{id} — refunded 주문에서 결제 폼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_refundedOrder_doesNotShowPaymentForm() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(refundedOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(refundedStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제하기 버튼이 없어야 함")
                .doesNotContain("결제하기");
    }

    // ============================================================
    // 헬퍼 — OrderResponse 빌더
    // ============================================================

    private OrderResponse buildOrderResponse(String status) {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "테스트 상품", "빨강 / L",
                List.of(new OrderItemOptionValueResponse("색상", "빨강", 0)),
                new BigDecimal("30000"), 1, new BigDecimal("30000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호"
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                status,
                List.of(item),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30000"),
                address,
                Instant.parse("2026-01-01T12:00:00Z"),
                List.of()
        );
    }

    private OrderResponse pendingOrderResponse() {
        return buildOrderResponse("pending");
    }

    private OrderResponse paidOrderResponse() {
        return buildOrderResponse("paid");
    }

    private OrderResponse cancelledOrderResponse() {
        return buildOrderResponse("cancelled");
    }

    private OrderResponse refundedOrderResponse() {
        return buildOrderResponse("refunded");
    }

    private OrderResponse preparingOrderResponse() {
        return buildOrderResponse("preparing");
    }

    private OrderResponse shippingOrderResponse() {
        return buildOrderResponse("shipping");
    }

    private OrderResponse deliveredOrderResponse() {
        return buildOrderResponse("delivered");
    }

    // ============================================================
    // 헬퍼 — PaymentStatusView 빌더
    // ============================================================

    /** 결제 가능 상태 (주문 pending && !paid → 결제 폼 노출). */
    private PaymentStatusView payableStatusView() {
        return new PaymentStatusView(
                1L,
                "none",
                false,
                true,   // payable = true → 결제 폼 노출
                new BigDecimal("30000"),
                null
        );
    }

    /** 결제 완료 상태 (paid → 결제 폼 미노출). */
    private PaymentStatusView paidStatusView() {
        return new PaymentStatusView(
                1L,
                "paid",
                true,
                false,  // payable = false → 결제 폼 미노출
                new BigDecimal("30000"),
                Instant.parse("2026-01-01T13:00:00Z")
        );
    }

    /** 취소 완료 상태 (payable=false). */
    private PaymentStatusView cancelledStatusView() {
        return new PaymentStatusView(
                1L,
                "cancelled",
                false,
                false,
                BigDecimal.ZERO,
                null
        );
    }

    /** 환불 완료 상태 (payable=false). */
    private PaymentStatusView refundedStatusView() {
        return new PaymentStatusView(
                1L,
                "refunded",
                false,
                false,
                BigDecimal.ZERO,
                null
        );
    }

    // ============================================================
    // 헬퍼 — OrderCancelResponse
    // ============================================================

    private OrderCancelResponse cancelledResponse() {
        return new OrderCancelResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "cancelled",
                false,
                0L,
                "KRW"
        );
    }
}
