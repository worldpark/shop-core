package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.common.exception.PaymentAmountMismatchException;
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
import com.shop.shop.payment.dto.PaymentResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 화면(templates/order/detail.html 결제 영역) Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증.
 * PaymentFacade·OrderFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(P1) GET /orders/{id} pending 주문 상세에 결제 폼(CSRF 히든) 렌더 + "결제 대기" 표시</li>
 *   <li>(P2) POST /orders/{id}/payment 결제 성공 → redirect /orders/{id} + flashSuccess</li>
 *   <li>(P3) GET /orders/{id} paid 주문 → 결제 폼 미노출 + "결제 완료" 표시</li>
 *   <li>(P4) 비인증 POST /orders/{id}/payment → 302 /login redirect</li>
 *   <li>(P5) POST /orders/{id}/payment BusinessException(409) → flashError + redirect:/orders/{id}</li>
 *   <li>(P6) POST /orders/{id}/payment BusinessException(400) → flashError + redirect:/orders/{id}</li>
 * </ul>
 *
 * <p>인가: SecurityConfig View 체인 /orders/[orderId]/payment hasRole("CONSUMER").
 * 미인증 시 302 /login (컨트롤러 도달 전 Security 처리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PaymentViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    // ============================================================
    // MockitoBean — 기존 view 테스트 패턴 준수
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
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(pendingOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(payableStatusView());
    }

    // ============================================================
    // (P1) GET /orders/{id} — pending 주문: 결제 폼 + "결제 대기" 표시
    // ============================================================

    @Test
    @DisplayName("(P1) GET /orders/{id} — pending 주문 상세에 결제 폼 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_pendingOrder_rendersPaymentForm() throws Exception {
        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제 폼 action이 /orders/1/payment여야 함")
                .contains("/orders/1/payment");
        assertThat(body).as("결제 폼 method=post 히든이 포함되어야 함")
                .contains("method");
        assertThat(body).as("결제하기 버튼이 렌더링되어야 함")
                .contains("결제하기");
    }

    @Test
    @DisplayName("(P1) GET /orders/{id} — pending 주문 상세에 CSRF 토큰 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_pendingOrder_formContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제 폼에 CSRF 토큰이 있어야 함")
                .contains("_csrf");
    }

    @Test
    @DisplayName("(P1) GET /orders/{id} — pending 주문 상세에 '결제 대기' 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_pendingOrder_showsPaymentPendingStatus() throws Exception {
        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제 대기 상태 텍스트가 렌더링되어야 함")
                .contains("결제 대기");
    }

    // ============================================================
    // (P2) POST /orders/{id}/payment — 결제 성공
    // ============================================================

    @Test
    @DisplayName("(P2) POST /orders/{id}/payment — 결제 성공 → redirect:/orders/{id} + flashSuccess")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void pay_success_redirectWithFlashSuccess() throws Exception {
        when(paymentFacade.pay(anyString(), anyLong(), any())).thenReturn(paidPaymentResponse());

        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "mock"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attribute("flashSuccess", "결제가 완료되었습니다."));
    }

    // ============================================================
    // (P3) GET /orders/{id} — paid 주문: 결제 폼 미노출 + "결제 완료" 표시
    // ============================================================

    @Test
    @DisplayName("(P3) GET /orders/{id} — paid 주문 → 결제 폼 미노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_paidOrder_doesNotRenderPaymentForm() throws Exception {
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제하기 버튼이 없어야 함")
                .doesNotContain("결제하기");
    }

    @Test
    @DisplayName("(P3) GET /orders/{id} — paid 주문 → '결제 완료' 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_paidOrder_showsPaidStatus() throws Exception {
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("결제 완료 상태 텍스트가 렌더링되어야 함")
                .contains("결제 완료");
    }

    // ============================================================
    // (P4) 비인증 POST /orders/{id}/payment → 302 /login
    // ============================================================

    @Test
    @DisplayName("(P4) 비인증 POST /orders/{id}/payment → 302 /login redirect")
    void pay_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "mock"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // (P5) POST /orders/{id}/payment — BusinessException(409)
    // ============================================================

    @Test
    @DisplayName("(P5) POST /orders/{id}/payment — 충돌 예외(409) → flashError + redirect:/orders/{id}")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void pay_conflictException_flashErrorAndRedirect() throws Exception {
        when(paymentFacade.pay(anyString(), anyLong(), any()))
                .thenThrow(new OrderConfirmationConflictException("주문 상태 충돌이 발생했습니다."));

        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "mock"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (P6) POST /orders/{id}/payment — BusinessException(400)
    // ============================================================

    @Test
    @DisplayName("(P6) POST /orders/{id}/payment — 금액 불일치(400) → flashError + redirect:/orders/{id}")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void pay_amountMismatch_flashErrorAndRedirect() throws Exception {
        when(paymentFacade.pay(anyString(), anyLong(), any()))
                .thenThrow(new PaymentAmountMismatchException("결제 금액이 주문 금액과 일치하지 않습니다."));

        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "mock")
                        .param("amount", "99999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderResponse pendingOrderResponse() {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "테스트 상품", "빨강 / L",
                List.of(new OrderItemOptionValueResponse("색상", "빨강", 0)),
                new BigDecimal("15000"), 2, new BigDecimal("30000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호"
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "pending",
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

    private PaymentResponse paidPaymentResponse() {
        return new PaymentResponse(
                10L,
                1L,
                "ORD-20260101-120000-ABCD1234",
                "paid",
                "mock",
                new BigDecimal("30000"),
                "MOCK-UUID-1234",
                Instant.parse("2026-01-01T13:00:00Z")
        );
    }
}
