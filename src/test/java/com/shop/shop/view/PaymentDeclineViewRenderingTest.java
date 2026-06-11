package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.PaymentDeclinedException;
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
import static org.mockito.ArgumentMatchers.any;
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
 * 결제 거절(017) View 렌더링 통합 테스트.
 *
 * <p>plan §2.2 / §5 "View(자동) — PaymentDeclineViewRenderingTest" 항목 구현.
 * PaymentFacade·OrderFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(D1) POST /orders/{id}/payment — facade가 PaymentDeclinedException(402) throw 시
 *       핸들러가 flashError(failureReason) + redirect:/orders/{id}.</li>
 *   <li>(D2) POST /orders/{id}/payment — flashError 메시지가 failureReason과 일치함.</li>
 *   <li>(D3) GET /orders/{id} — 거절 후 주문 pending 유지 상태에서
 *       payment.payable=true → 결제 폼 재노출.</li>
 *   <li>(D4) GET /orders/{id} — paid 주문은 결제 폼 미노출(회귀 확인).</li>
 *   <li>(D5) GET /orders/{id} — pending 주문 결제 폼 재노출 시 "결제 대기" 표시.</li>
 * </ul>
 *
 * <p>인가: SecurityConfig View 체인 /orders/[orderId]/payment hasRole("CONSUMER").
 * 미인증 시 컨트롤러 도달 전 302 /login.
 *
 * <p>핵심 보장:
 * - PaymentDeclinedException extends BusinessException 이므로
 *   OrderViewController.pay의 catch(BusinessException e) 분기가 수정 없이 거절을 처리한다.
 * - layout/base.html이 fragments/messages :: messages를 포함하므로
 *   flashError가 detail 화면에 렌더된다.
 * - detail.html의 th:if="${payment.payable}"이 failed(paid=false) 상태에서도
 *   payable=true일 때 결제 폼을 재노출한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class PaymentDeclineViewRenderingTest {

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

    private static final String FAILURE_REASON = "카드사에서 결제가 거절되었습니다.";

    @BeforeEach
    void setUp() {
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(pendingOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(payableStatusView());
    }

    // ============================================================
    // (D1) POST /orders/{id}/payment — PaymentDeclinedException → flashError + redirect
    // ============================================================

    @Test
    @DisplayName("(D1) POST /orders/{id}/payment — PaymentDeclinedException → redirect:/orders/{id}")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void pay_declined_redirectsToOrderDetail() throws Exception {
        when(paymentFacade.pay(anyString(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "virtual_account"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"));
    }

    // ============================================================
    // (D2) POST /orders/{id}/payment — flashError 메시지 = failureReason
    // ============================================================

    @Test
    @DisplayName("(D2) POST /orders/{id}/payment — PaymentDeclinedException → flashError 메시지=failureReason")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void pay_declined_flashErrorContainsFailureReason() throws Exception {
        when(paymentFacade.pay(anyString(), anyLong(), any()))
                .thenThrow(new PaymentDeclinedException(FAILURE_REASON));

        mockMvc.perform(post("/orders/1/payment")
                        .with(csrf())
                        .param("method", "virtual_account"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"))
                .andExpect(flash().attribute("flashError", FAILURE_REASON));
    }

    // ============================================================
    // (D3) GET /orders/{id} — 거절 후 pending 유지: payment.payable=true → 결제 폼 재노출
    // ============================================================

    @Test
    @DisplayName("(D3) GET /orders/{id} — 거절 후 pending 상태(payment.payable=true) → 결제 폼 재노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_afterDecline_pendingOrder_rendersPaymentForm() throws Exception {
        // 거절 후: 주문 pending 유지 + payable=true (failed는 paid=false라 payable 조건 흡수)
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(payableStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("거절 후 결제 폼 action이 /orders/1/payment여야 함")
                .contains("/orders/1/payment");
        assertThat(body).as("거절 후 결제하기 버튼이 재노출되어야 함")
                .contains("결제하기");
    }

    // ============================================================
    // (D4) GET /orders/{id} — paid 주문은 결제 폼 미노출 (회귀 확인)
    // ============================================================

    @Test
    @DisplayName("(D4) GET /orders/{id} — paid 주문(payable=false) → 결제 폼 미노출 (회귀)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_paidOrder_doesNotRenderPaymentForm() throws Exception {
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(paidStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("paid 주문에 결제하기 버튼이 없어야 함")
                .doesNotContain("결제하기");
    }

    // ============================================================
    // (D5) GET /orders/{id} — pending(거절 후) 상태 표시: "결제 대기"
    // ============================================================

    @Test
    @DisplayName("(D5) GET /orders/{id} — 거절 후 pending 상태 → '결제 대기' 표시 (failed도 결제 대기로 흡수)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_afterDecline_showsPaymentPendingStatus() throws Exception {
        // failed는 paid=false → th:unless="${payment.paid}"로 "결제 대기" 렌더
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(payableStatusView());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("거절 후 '결제 대기' 상태 텍스트가 렌더링되어야 함")
                .contains("결제 대기");
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
                Instant.parse("2026-01-01T12:00:00Z")
        );
    }

    /**
     * 결제 가능 상태: 주문 pending && !paid → 결제 폼 노출.
     * 거절 후에도 주문이 pending 유지되고 paid=false이므로 payable=true가 된다(016 로직 흡수).
     */
    private PaymentStatusView payableStatusView() {
        return new PaymentStatusView(
                1L,
                "failed",  // 거절 후 status=failed, 하지만 paid=false → payable=true로 흡수
                false,
                true,      // payable=true → 결제 폼 재노출
                new BigDecimal("30000"),
                null
        );
    }

    /** 결제 완료 상태: paid=true, payable=false → 결제 폼 미노출. */
    private PaymentStatusView paidStatusView() {
        return new PaymentStatusView(
                1L,
                "paid",
                true,
                false,     // payable=false → 결제 폼 미노출
                new BigDecimal("30000"),
                Instant.parse("2026-01-01T13:00:00Z")
        );
    }
}
