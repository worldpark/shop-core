package com.shop.shop.web.order;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.EmptyCartException;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.OrderCheckoutResponse;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.dto.OrderItemOptionValueResponse;
import com.shop.shop.order.dto.OrderItemResponse;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.OrderSummaryResponse;
import com.shop.shop.order.dto.ShippingAddressResponse;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.dto.PaymentStatusView;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentFacade;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * OrderViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>OrderFacade(@MockitoBean)를 통해 facade 배선·모델 키·view name·redirect·flashError를 검증.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /checkout: 비인증 302, CONSUMER 200, view name order/checkout, 모델 checkout 존재</li>
 *   <li>GET /checkout: orderFacade.getCheckout(email) 호출 검증</li>
 *   <li>POST /orders: 성공 → redirect:/orders/{orderId}</li>
 *   <li>POST /orders: 검증 실패 → flashError + redirect:/checkout</li>
 *   <li>POST /orders: 도메인 예외 → flashError + redirect:/checkout</li>
 *   <li>GET /orders: CONSUMER 200, view name order/list, 모델 orders 존재</li>
 *   <li>GET /orders/{id}: CONSUMER 200, view name order/detail, 모델 order 존재</li>
 *   <li>GET /orders/{id}: OrderNotFoundException → error 뷰</li>
 *   <li>email이 facade 메서드에 전달됨</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class OrderViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    private OrderFacade orderFacade;

    @MockitoBean
    private PaymentFacade paymentFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    private static final String USER_EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        when(orderFacade.getCheckout(anyString())).thenReturn(sampleCheckoutResponse());
        when(orderFacade.createOrder(anyString(), any())).thenReturn(sampleOrderResponse());
        when(orderFacade.getMyOrders(anyString(), any())).thenReturn(
                new PageImpl<>(List.of(sampleOrderSummaryResponse()), PageRequest.of(0, 10), 1));
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(sampleOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(samplePaymentStatusView());
    }

    // ============================================================
    // GET /checkout
    // ============================================================

    @Test
    @DisplayName("GET /checkout — 비인증 → /login redirect (302)")
    void checkout_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /checkout — CONSUMER → 200")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void checkout_consumer_returns200() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /checkout — view name order/checkout")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void checkout_returnsCheckoutView() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andExpect(view().name("order/checkout"));
    }

    @Test
    @DisplayName("GET /checkout — model에 checkout 키 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void checkout_modelContainsCheckout() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("checkout"));
    }

    @Test
    @DisplayName("GET /checkout — orderFacade.getCheckout(email) 호출 검증")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void checkout_delegatesToFacadeWithEmail() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk());

        verify(orderFacade).getCheckout(USER_EMAIL);
    }

    // ============================================================
    // POST /orders — 주문 생성
    // ============================================================

    @Test
    @DisplayName("POST /orders — 성공 → redirect:/orders/{orderId}")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_success_redirectsToOrderDetail() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/1"));
    }

    @Test
    @DisplayName("POST /orders — recipient 누락 → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_missingRecipient_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/checkout"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /orders — phone 누락 → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_missingPhone_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/checkout"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /orders — 재고 부족(409) → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_insufficientStock_flashErrorAndRedirect() throws Exception {
        when(orderFacade.createOrder(anyString(), any()))
                .thenThrow(new InsufficientStockException());

        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/checkout"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /orders — 빈 장바구니(400) → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_emptyCart_flashErrorAndRedirect() throws Exception {
        when(orderFacade.createOrder(anyString(), any()))
                .thenThrow(new EmptyCartException());

        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/checkout"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /orders — email이 facade.createOrder에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_emailPassedToFacade() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection());

        verify(orderFacade).createOrder(eq(USER_EMAIL), any());
    }

    @Test
    @DisplayName("POST /orders — userCouponId 미선택 시 null 전달 (회귀)")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_noUserCouponId_passesNullToRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection());

        verify(orderFacade).createOrder(eq(USER_EMAIL),
                argThat(req -> req.userCouponId() == null));
    }

    @Test
    @DisplayName("POST /orders — userCouponId 선택 시 해당 값이 OrderCreateRequest에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void createOrder_withUserCouponId_passesIdToRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구")
                        .param("userCouponId", "42"))
                .andExpect(status().is3xxRedirection());

        verify(orderFacade).createOrder(eq(USER_EMAIL),
                argThat(req -> Long.valueOf(42L).equals(req.userCouponId())));
    }

    // ============================================================
    // GET /orders — 주문 목록
    // ============================================================

    @Test
    @DisplayName("GET /orders — CONSUMER → 200")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void listOrders_consumer_returns200() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /orders — view name order/list")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void listOrders_returnsOrderListView() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(view().name("order/list"));
    }

    @Test
    @DisplayName("GET /orders — model에 orders 키 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void listOrders_modelContainsOrders() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("orders"));
    }

    @Test
    @DisplayName("GET /orders — orderFacade.getMyOrders(email, pageable) 호출 검증")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void listOrders_delegatesToFacadeWithEmail() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());

        verify(orderFacade).getMyOrders(eq(USER_EMAIL), any());
    }

    // ============================================================
    // GET /orders/{orderId} — 주문 상세
    // ============================================================

    @Test
    @DisplayName("GET /orders/{id} — CONSUMER → 200")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void orderDetail_consumer_returns200() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /orders/{id} — view name order/detail")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void orderDetail_returnsOrderDetailView() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("order/detail"));
    }

    @Test
    @DisplayName("GET /orders/{id} — model에 order 키 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void orderDetail_modelContainsOrder() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("order"));
    }

    @Test
    @DisplayName("GET /orders/{id} — email이 facade.getMyOrder에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void orderDetail_emailAndOrderIdPassedToFacade() throws Exception {
        mockMvc.perform(get("/orders/42"))
                .andExpect(status().isOk());

        verify(orderFacade).getMyOrder(eq(USER_EMAIL), eq(42L));
    }

    @Test
    @DisplayName("GET /orders/{id} — OrderNotFoundException(404) → error 뷰 렌더링")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void orderDetail_notFoundOrder_returns404() throws Exception {
        when(orderFacade.getMyOrder(anyString(), eq(999L)))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/orders/999"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderCheckoutResponse sampleCheckoutResponse() {
        OrderItemResponse item = new OrderItemResponse(
                null, 10L, "테스트 상품", "빨강 / L",
                List.of(new OrderItemOptionValueResponse("색상", "빨강", 0)),
                new BigDecimal("15000"), 2, new BigDecimal("30000")
        );
        return new OrderCheckoutResponse(
                List.of(item),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30000"),
                true,
                List.of() // 057: applicableCoupons — 테스트 픽스처에는 빈 목록
        );
    }

    private OrderSummaryResponse sampleOrderSummaryResponse() {
        return new OrderSummaryResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "pending",
                "테스트 상품",
                1,
                new BigDecimal("30000"),
                Instant.parse("2026-01-01T12:00:00Z")
        );
    }

    private OrderResponse sampleOrderResponse() {
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

    private PaymentStatusView samplePaymentStatusView() {
        return new PaymentStatusView(
                1L,
                "none",
                false,
                true,
                new BigDecimal("30000"),
                null
        );
    }
}
