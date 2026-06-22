package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.OrderCheckoutResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 화면(templates/order/*.html) Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증.
 * OrderFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /checkout: CONSUMER 인증 → 200 렌더링</li>
 *   <li>GET /checkout: 비인증 → 302 /login redirect</li>
 *   <li>GET /checkout: 주문 가능 항목·금액·배송지 폼 CSRF 렌더링</li>
 *   <li>GET /checkout: hasItems=false 이면 빈 안내 표시</li>
 *   <li>POST /orders: 주문 성공 → redirect:/orders/{orderId}</li>
 *   <li>POST /orders: 폼 검증 실패 → flashError + redirect:/checkout</li>
 *   <li>POST /orders: 도메인 예외(409) → flashError + redirect:/checkout</li>
 *   <li>GET /orders: CONSUMER → 목록 렌더링</li>
 *   <li>GET /orders/{id}: CONSUMER → 상세 렌더링</li>
 *   <li>GET /orders/{id}: OrderNotFoundException(404) → error 뷰</li>
 *   <li>nav에 /orders 링크(CONSUMER)</li>
 *   <li>cart index 주문하기 → /checkout</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class OrderViewRenderingTest {

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

    @BeforeEach
    void setUp() {
        when(orderFacade.getCheckout(anyString())).thenReturn(sampleCheckoutResponse());
        when(orderFacade.getMyOrders(anyString(), any())).thenReturn(
                new PageImpl<>(List.of(sampleOrderSummaryResponse()), PageRequest.of(0, 10), 1));
        when(orderFacade.getMyOrder(anyString(), anyLong())).thenReturn(sampleOrderResponse());
        when(paymentFacade.getPaymentStatus(anyString(), anyLong())).thenReturn(samplePaymentStatusView());
    }

    // ============================================================
    // (O1) GET /checkout — 비인증/인증 접근
    // ============================================================

    @Test
    @DisplayName("(O1) GET /checkout — 비인증 → 302 /login redirect")
    void checkout_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("(O1) GET /checkout — CONSUMER 인증 → 200")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_consumer_returns200() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // (O2) GET /checkout — 렌더링 내용 검증
    // ============================================================

    @Test
    @DisplayName("(O2) GET /checkout — 주문 가능 항목 상품명 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_rendersProductName() throws Exception {
        String body = mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명이 렌더링되어야 함").contains("테스트 상품");
    }

    @Test
    @DisplayName("(O2) GET /checkout — 금액(finalAmount) 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_rendersFinalAmount() throws Exception {
        String body = mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("finalAmount(30,000)이 렌더링되어야 함").contains("30,000");
    }

    @Test
    @DisplayName("(O2) GET /checkout — 배송지 폼 CSRF 토큰 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_formContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송지 폼에 CSRF 토큰이 있어야 함").contains("_csrf");
        assertThat(body).as("배송지 폼 action이 /orders여야 함").contains("/orders");
    }

    @Test
    @DisplayName("(O2) GET /checkout — hasItems=false 이면 빈 안내 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_emptyItems_showsEmptyMessage() throws Exception {
        when(orderFacade.getCheckout(anyString())).thenReturn(emptyCheckoutResponse());

        String body = mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("빈 안내 메시지가 있어야 함").contains("주문 가능한 상품이 없습니다");
    }

    // ============================================================
    // (O3) POST /orders — 주문 생성
    // ============================================================

    @Test
    @DisplayName("(O3) POST /orders — 주문 성공 → redirect:/orders/{orderId}")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void createOrder_success_redirectsToOrderDetail() throws Exception {
        when(orderFacade.createOrder(anyString(), any())).thenReturn(sampleOrderResponse());

        mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("recipient", "홍길동")
                        .param("phone", "010-1234-5678")
                        .param("postcode", "12345")
                        .param("address1", "서울시 강남구"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/orders/*"));
    }

    @Test
    @DisplayName("(O3) POST /orders — 필수 필드 누락(recipient) → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void createOrder_missingRecipient_flashErrorAndRedirectToCheckout() throws Exception {
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
    @DisplayName("(O3) POST /orders — 도메인 예외(409 재고부족) → flashError + redirect:/checkout")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void createOrder_domainException_flashErrorAndRedirectToCheckout() throws Exception {
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

    // ============================================================
    // (O4) GET /orders — 주문 목록
    // ============================================================

    @Test
    @DisplayName("(O4) GET /orders — CONSUMER 인증 → 200")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void listOrders_consumer_returns200() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("(O4) GET /orders — 비인증 → 302 /login redirect")
    void listOrders_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("(O4) GET /orders — 주문번호 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void listOrders_rendersOrderNumber() throws Exception {
        String body = mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("주문번호가 렌더링되어야 함").contains("ORD-20260101-120000-ABCD1234");
        assertThat(body).as("주문일시가 Asia/Seoul(KST)로 변환되어 렌더링되어야 함 (12:00Z → 21:00 KST)")
                .contains("2026-01-01 21:00");
    }

    // ============================================================
    // (O5) GET /orders/{id} — 주문 상세
    // ============================================================

    @Test
    @DisplayName("(O5) GET /orders/{id} — CONSUMER 인증 → 200")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_consumer_returns200() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("(O5) GET /orders/{id} — 상세 정보 렌더링 (상품명/배송지)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_rendersSnapshot() throws Exception {
        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("주문 상품명이 렌더링되어야 함").contains("테스트 상품");
        assertThat(body).as("배송지 수령인이 렌더링되어야 함").contains("홍길동");
        assertThat(body).as("주문일시가 Asia/Seoul(KST)로 변환되어 렌더링되어야 함 (12:00Z → 21:00:00 KST)")
                .contains("2026-01-01 21:00:00");
    }

    @Test
    @DisplayName("(O5) GET /orders/{id} — OrderNotFoundException(404) → error 뷰")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_notFound_returnsErrorView() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/orders/999"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // (O6) nav에 /orders 링크
    // ============================================================

    @Test
    @DisplayName("(O6) GET /checkout — nav에 /orders 링크 포함 (CONSUMER)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void checkout_navContainsOrdersLink() throws Exception {
        String body = mockMvc.perform(get("/checkout"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /orders 링크가 있어야 함").contains("/orders");
        assertThat(body).as("nav에 주문 내역 텍스트가 있어야 함").contains("주문 내역");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderCheckoutResponse sampleCheckoutResponse() {
        OrderItemResponse item = new OrderItemResponse(
                null, 10L, "테스트 상품", "빨강 / L",
                List.of(new OrderItemOptionValueResponse("색상", "빨강", 0),
                        new OrderItemOptionValueResponse("사이즈", "L", 1)),
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

    private OrderCheckoutResponse emptyCheckoutResponse() {
        return new OrderCheckoutResponse(
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                List.of() // 057: applicableCoupons — 빈 장바구니
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
