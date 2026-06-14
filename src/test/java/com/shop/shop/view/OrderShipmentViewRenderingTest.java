package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.OrderItemOptionValueResponse;
import com.shop.shop.order.dto.OrderItemResponse;
import com.shop.shop.order.dto.OrderResponse;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소비자 주문 상세 화면(templates/order/detail.html) 배송 목록 블록 렌더링 통합 테스트 (020).
 *
 * <p>order.shipments(List&lt;ShipmentResponse&gt;) 렌더링을 검증한다.
 * 기존 OrderViewRenderingTest·OrderCancelViewRenderingTest의 MockMvc 설정·시큐리티·모델 stubbing 패턴 재사용.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(D1) GET /orders/{id} — shipping 배송: carrier·trackingNumber·shippedAt 표시</li>
 *   <li>(D2) GET /orders/{id} — preparing 배송: 추적정보(carrier/trackingNumber/shippedAt) 미표시</li>
 *   <li>(D3) GET /orders/{id} — 배송 목록 없음: 배송 정보 섹션 미표시</li>
 *   <li>(D4) GET /orders/{id} — 배송 상품명·수량 표시</li>
 *   <li>(D5) GET /orders/{id} — shipping 배송 상태 라벨 '배송 중' 표시</li>
 *   <li>(D6) GET /orders/{id} — preparing 배송 상태 라벨 '배송 준비 중' 표시</li>
 *   <li>(D7) GET /orders/list — shipping 주문 상태 '배송 중' 라벨 표시 (list.html 라벨 보강 확인)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class OrderShipmentViewRenderingTest {

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


    @BeforeEach
    void setUp() {
        when(paymentFacade.getPaymentStatus(anyString(), anyLong()))
                .thenReturn(samplePaymentStatusView());
    }

    // ============================================================
    // (D1) shipping 배송: 추적정보 표시
    // ============================================================

    @Test
    @DisplayName("(D1) GET /orders/{id} — shipping 배송에 carrier·trackingNumber·shippedAt 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_shippingShipment_showsTrackingInfo() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithShippingShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("택배사명이 렌더링되어야 함").contains("CJ대한통운");
        assertThat(body).as("운송장번호가 렌더링되어야 함").contains("1234567890");
        // shippedAt: 2026-06-11T10:00:00Z → Asia/Seoul KST = 2026-06-11 19:00:00
        assertThat(body).as("배송 시작 시각이 Asia/Seoul KST로 변환되어 렌더링되어야 함")
                .contains("2026-06-11 19:00:00");
    }

    // ============================================================
    // (D2) preparing 배송: 추적정보 미표시
    // ============================================================

    @Test
    @DisplayName("(D2) GET /orders/{id} — preparing 배송에는 carrier·trackingNumber 미표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_preparingShipment_hidesTrackingInfo() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithPreparingShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // preparing 배송은 추적정보 섹션(shipment-tracking-info) 미렌더
        assertThat(body).as("preparing 배송에는 택배사명이 없어야 함")
                .doesNotContain("CJ대한통운");
        assertThat(body).as("preparing 배송에는 운송장번호가 없어야 함")
                .doesNotContain("1234567890");
    }

    // ============================================================
    // (D3) 배송 목록 없음 → 배송 정보 섹션 미표시
    // ============================================================

    @Test
    @DisplayName("(D3) GET /orders/{id} — 배송 목록 없음 → 배송 정보 섹션 미표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_noShipments_hidesShipmentSection() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithNoShipments());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송 목록 없음 시 배송 정보 섹션이 없어야 함")
                .doesNotContain("배송 정보");
    }

    // ============================================================
    // (D4) 배송 포함 상품명·수량 표시
    // ============================================================

    @Test
    @DisplayName("(D4) GET /orders/{id} — 배송에 포함된 상품명·수량 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_shippingShipment_showsItems() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithShippingShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송 포함 상품명이 렌더링되어야 함").contains("배송 테스트 상품");
        assertThat(body).as("배송 포함 수량이 렌더링되어야 함").contains("x2");
    }

    // ============================================================
    // (D5) shipping 배송 상태 라벨
    // ============================================================

    @Test
    @DisplayName("(D5) GET /orders/{id} — shipping 배송 상태 라벨 '배송 중' 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_shippingShipment_showsShippingLabel() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithShippingShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송 상태 라벨 '배송 중'이 렌더링되어야 함").contains("배송 중");
    }

    // ============================================================
    // (D6) preparing 배송 상태 라벨
    // ============================================================

    @Test
    @DisplayName("(D6) GET /orders/{id} — preparing 배송 상태 라벨 '배송 준비 중' 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_preparingShipment_showsPreparingLabel() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithPreparingShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송 상태 라벨 '배송 준비 중'이 렌더링되어야 함").contains("배송 준비 중");
    }

    // ============================================================
    // (D7) list.html — shipping 주문 '배송 중' 라벨 (list.html 보강 확인)
    // ============================================================

    @Test
    @DisplayName("(D7) GET /orders — shipping 주문 상태 '배송 중' 라벨 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void listOrders_shippingOrder_showsShippingLabel() throws Exception {
        com.shop.shop.order.dto.OrderSummaryResponse shippingOrder =
                new com.shop.shop.order.dto.OrderSummaryResponse(
                        2L,
                        "ORD-20260611-001",
                        "shipping",
                        "배송 테스트 상품",
                        1,
                        new BigDecimal("30000"),
                        Instant.parse("2026-06-11T10:00:00Z")
                );
        when(orderFacade.getMyOrders(anyString(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(shippingOrder),
                        org.springframework.data.domain.PageRequest.of(0, 10),
                        1));

        String body = mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("shipping 주문 상태 라벨 '배송 중'이 렌더링되어야 함").contains("배송 중");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderResponse orderResponseWithShippingShipment() {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "테스트 상품", null,
                List.of(new OrderItemOptionValueResponse("색상", "빨강", 0)),
                new BigDecimal("15000"), 2, new BigDecimal("30000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", null
        );
        ShipmentItemResponse si = new ShipmentItemResponse(1L, "배송 테스트 상품", 2);
        ShipmentResponse shipment = new ShipmentResponse(
                30L, 1L, "shipping",
                "CJ대한통운", "1234567890",
                Instant.parse("2026-06-11T10:00:00Z"), null,
                List.of(si)
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "shipping",
                List.of(item),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30000"),
                address,
                Instant.parse("2026-06-11T10:00:00Z"),
                List.of(shipment)
        );
    }

    private OrderResponse orderResponseWithPreparingShipment() {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "테스트 상품", null,
                List.of(),
                new BigDecimal("15000"), 1, new BigDecimal("15000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", null
        );
        ShipmentItemResponse si = new ShipmentItemResponse(1L, "테스트 상품", 1);
        ShipmentResponse shipment = new ShipmentResponse(
                31L, 1L, "preparing",
                null, null, null, null,
                List.of(si)
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "preparing",
                List.of(item),
                new BigDecimal("15000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("15000"),
                address,
                Instant.parse("2026-06-11T10:00:00Z"),
                List.of(shipment)
        );
    }

    private OrderResponse orderResponseWithNoShipments() {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "테스트 상품", null,
                List.of(),
                new BigDecimal("15000"), 1, new BigDecimal("15000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", null
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "paid",
                List.of(item),
                new BigDecimal("15000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("15000"),
                address,
                Instant.parse("2026-06-11T10:00:00Z"),
                List.of()
        );
    }

    private PaymentStatusView samplePaymentStatusView() {
        return new PaymentStatusView(
                1L,
                "none",
                false,
                false,
                new BigDecimal("30000"),
                null
        );
    }
}
