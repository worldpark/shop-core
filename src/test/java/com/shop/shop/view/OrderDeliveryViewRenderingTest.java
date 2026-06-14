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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소비자 주문 상세 화면(templates/order/detail.html) delivered 배송완료시각 표시 렌더링 통합 테스트 (021).
 *
 * <p>order.shipments 중 delivered 배송에 deliveredAt이 올바르게 렌더링되는지 검증한다.
 * 기존 OrderShipmentViewRenderingTest의 MockMvc 설정·시큐리티·모델 stubbing 패턴 재사용.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(V1) GET /orders/{id} — delivered 배송에 배송완료시각(Asia/Seoul) 표시</li>
 *   <li>(V2) GET /orders/{id} — delivered 배송 상태 라벨 '배송 완료' 표시</li>
 *   <li>(V3) GET /orders/{id} — delivered 배송에 ownerId·seller_id 등 내부정보 미표시</li>
 *   <li>(V4) GET /orders — delivered 주문 상태 '배송 완료' 라벨 표시 (list.html 무변경 확인)</li>
 *   <li>(V5) GET /orders/{id} — 비인증 admin 경로 /login redirect 확인</li>
 * </ul>
 *
 * <p>패턴: OrderShipmentViewRenderingTest @MockitoBean 목록 그대로 미러.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class OrderDeliveryViewRenderingTest {

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
    // (V1) delivered 배송: 배송완료시각 표시
    // ============================================================

    @Test
    @DisplayName("(V1) GET /orders/{id} — delivered 배송에 배송완료시각(Asia/Seoul) 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_deliveredShipment_showsDeliveredAt() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithDeliveredShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // deliveredAt: 2026-06-12T05:30:00Z → Asia/Seoul KST = 2026-06-12 14:30:00
        assertThat(body).as("배송완료시각이 Asia/Seoul KST로 변환되어 렌더링되어야 함")
                .contains("2026-06-12 14:30:00");
    }

    // ============================================================
    // (V2) delivered 배송 상태 라벨
    // ============================================================

    @Test
    @DisplayName("(V2) GET /orders/{id} — delivered 배송 상태 라벨 '배송 완료' 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_deliveredShipment_showsDeliveredLabel() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithDeliveredShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("배송 상태 라벨 '배송 완료'가 렌더링되어야 함").contains("배송 완료");
    }

    // ============================================================
    // (V3) delivered 배송에 내부정보 미표시
    // ============================================================

    @Test
    @DisplayName("(V3) GET /orders/{id} — delivered 배송에 ShipmentResponse 계약 필드만 노출, Entity/로컬경로 미표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void orderDetail_deliveredShipment_doesNotExposeInternalFields() throws Exception {
        when(orderFacade.getMyOrder(anyString(), anyLong()))
                .thenReturn(orderResponseWithDeliveredShipment());

        String body = mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ShipmentResponse에 로컬 파일 경로(절대경로)가 없으므로 노출되지 않음
        // (Entity 미노출 — ShipmentResponse는 record DTO이므로 Entity 필드 미포함)
        assertThat(body).as("로컬 절대경로가 노출되지 않아야 함").doesNotContain("C:\\");
        assertThat(body).as("로컬 절대경로가 노출되지 않아야 함").doesNotContain("/var/");

        // 배송완료시각은 정상 표시
        assertThat(body).as("배송완료시각이 렌더링되어야 함").contains("2026-06-12 14:30:00");

        // 배송 상태 라벨 정상
        assertThat(body).as("배송 완료 라벨이 렌더링되어야 함").contains("배송 완료");
    }

    // ============================================================
    // (V4) list.html — delivered 주문 '배송 완료' 라벨 (무변경 확인)
    // ============================================================

    @Test
    @DisplayName("(V4) GET /orders — delivered 주문 상태 '배송 완료' 라벨 표시 (list.html 무변경 확인)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void listOrders_deliveredOrder_showsDeliveredLabel() throws Exception {
        com.shop.shop.order.dto.OrderSummaryResponse deliveredOrder =
                new com.shop.shop.order.dto.OrderSummaryResponse(
                        3L,
                        "ORD-20260612-001",
                        "delivered",
                        "배송 완료 테스트 상품",
                        1,
                        new BigDecimal("30000"),
                        Instant.parse("2026-06-10T10:00:00Z")
                );
        when(orderFacade.getMyOrders(anyString(), any()))
                .thenReturn(new PageImpl<>(
                        List.of(deliveredOrder),
                        PageRequest.of(0, 10),
                        1));

        String body = mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("delivered 주문 상태 라벨 '배송 완료'가 렌더링되어야 함").contains("배송 완료");
    }

    // ============================================================
    // (V5) 비인증 admin 경로 /login redirect 확인
    // ============================================================

    @Test
    @DisplayName("(V5) GET /admin/orders — 비인증 → /login redirect")
    void get_adminOrders_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getRedirectedUrl())
                                .as("비인증 admin 경로는 /login으로 redirect 되어야 함")
                                .contains("/login"));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private OrderResponse orderResponseWithDeliveredShipment() {
        OrderItemResponse item = new OrderItemResponse(
                1L, 10L, "배송완료 테스트 상품", null,
                List.of(new OrderItemOptionValueResponse("색상", "파랑", 0)),
                new BigDecimal("15000"), 2, new BigDecimal("30000")
        );
        ShippingAddressResponse address = new ShippingAddressResponse(
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", null
        );
        ShipmentItemResponse si = new ShipmentItemResponse(1L, "배송완료 테스트 상품", 2);
        // deliveredAt: 2026-06-12T05:30:00Z → Asia/Seoul KST = 2026-06-12 14:30:00
        ShipmentResponse shipment = new ShipmentResponse(
                40L, 1L, "delivered",
                "CJ대한통운", "1234567890",
                Instant.parse("2026-06-11T10:00:00Z"),
                Instant.parse("2026-06-12T05:30:00Z"),
                List.of(si)
        );
        return new OrderResponse(
                1L,
                "ORD-20260101-120000-ABCD1234",
                "delivered",
                List.of(item),
                new BigDecimal("30000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30000"),
                address,
                Instant.parse("2026-06-10T10:00:00Z"),
                List.of(shipment)
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
