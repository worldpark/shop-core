package com.shop.shop.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.service.OrderFulfillmentService;
import com.shop.shop.payment.repository.PaymentRepository;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminShipmentRestController.deliver() + SecurityConfig REST 체인 MockMvc 통합 테스트 (Task 021).
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/admin/shipments/{id}/deliver: 성공 200 + DeliverResponse</li>
 *   <li>잘못된 전이 409 + ErrorResponse(내부 정보 비노출)</li>
 *   <li>미존재 배송 404</li>
 *   <li>권한: ADMIN 200·비인증 401·비ADMIN(CONSUMER/SELLER) 403</li>
 *   <li>응답 내부정보(Entity/ownerId) 비노출, status lowercase</li>
 *   <li>멱등성: 이미 delivered → 200 (orderDelivered=true)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminDeliverRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private OrderFulfillmentService orderFulfillmentService;

    // 컨텍스트 로드에 필요한 Repository mocks
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

    private static final Instant SHIPPED_AT = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-06-12T10:00:00Z");

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // 성공 200 — shipping → delivered
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/admin/shipments/{id}/deliver — ADMIN → 200 + DeliverResponse(status=delivered)")
    void deliver_admin_returns_200_statusDelivered() throws Exception {
        ShipmentResponse shipment = new ShipmentResponse(
                100L, 1L, "delivered",
                "CJ대한통운", "TRK-001", SHIPPED_AT, DELIVERED_AT,
                List.of(new ShipmentItemResponse(10L, "상품A", 2)));
        DeliverResponse mockResponse = new DeliverResponse(shipment, true);

        when(orderFulfillmentService.deliver(anyLong())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.shipmentId").value(100))
                .andExpect(jsonPath("$.shipment.status").value("delivered"))
                .andExpect(jsonPath("$.orderDelivered").value(true));
    }

    @Test
    @DisplayName("POST — 응답에 deliveredAt 포함")
    void deliver_admin_responseIncludesDeliveredAt() throws Exception {
        ShipmentResponse shipment = new ShipmentResponse(
                100L, 1L, "delivered", "CJ", "TRK", SHIPPED_AT, DELIVERED_AT,
                List.of());
        DeliverResponse mockResponse = new DeliverResponse(shipment, true);

        when(orderFulfillmentService.deliver(anyLong())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.deliveredAt").isString());
    }

    @Test
    @DisplayName("POST — 멀티 배송 일부 완료 → orderDelivered=false")
    void deliver_multiShipment_partialComplete_orderDeliveredFalse() throws Exception {
        ShipmentResponse shipment = new ShipmentResponse(
                100L, 1L, "delivered", "CJ", "TRK", SHIPPED_AT, DELIVERED_AT,
                List.of());
        DeliverResponse mockResponse = new DeliverResponse(shipment, false);

        when(orderFulfillmentService.deliver(anyLong())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderDelivered").value(false));
    }

    // ============================================================
    // 멱등성 — 이미 delivered → 200
    // ============================================================

    @Test
    @DisplayName("POST — 이미 delivered 배송 → 멱등 200 (orderDelivered=true)")
    void deliver_alreadyDelivered_idempotent_200() throws Exception {
        ShipmentResponse shipment = new ShipmentResponse(
                100L, 1L, "delivered", "CJ", "기존TRK", SHIPPED_AT, DELIVERED_AT, List.of());
        DeliverResponse mockResponse = new DeliverResponse(shipment, true);

        when(orderFulfillmentService.deliver(anyLong())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("delivered"))
                .andExpect(jsonPath("$.orderDelivered").value(true));
    }

    // ============================================================
    // 상태 충돌 409
    // ============================================================

    @Test
    @DisplayName("POST — 잘못된 전이 → 409 + ErrorResponse(내부 정보 비노출)")
    void deliver_conflict_returns_409() throws Exception {
        when(orderFulfillmentService.deliver(anyLong()))
                .thenThrow(new OrderFulfillmentConflictException("배송 상태 충돌로 완료할 수 없습니다."));

        String responseBody = mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .doesNotContain("\"stackTrace\"")
                .doesNotContain("\"orderId\"");
    }

    // ============================================================
    // 미존재 배송 404
    // ============================================================

    @Test
    @DisplayName("POST — 미존재 배송 → 404 + ErrorResponse")
    void deliver_shipmentNotFound_returns_404() throws Exception {
        when(orderFulfillmentService.deliver(anyLong()))
                .thenThrow(new ShipmentNotFoundException());

        mockMvc.perform(post("/api/v1/admin/shipments/9999/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // 비인증 401
    // ============================================================

    @Test
    @DisplayName("POST — 비인증 Bearer 없음 → 401 JSON")
    void deliver_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ============================================================
    // 비ADMIN 403
    // ============================================================

    @Test
    @DisplayName("POST — CONSUMER Bearer → 403 JSON")
    void deliver_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST — SELLER Bearer → 403 JSON")
    void deliver_seller_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ============================================================
    // 응답 status lowercase 검증
    // ============================================================

    @Test
    @DisplayName("응답 shipment.status는 lowercase(delivered) — 대문자 미사용")
    void deliver_responseStatusIsLowercase() throws Exception {
        ShipmentResponse shipment = new ShipmentResponse(
                100L, 1L, "delivered", "CJ", "TRK", SHIPPED_AT, DELIVERED_AT, List.of());
        DeliverResponse mockResponse = new DeliverResponse(shipment, true);

        when(orderFulfillmentService.deliver(anyLong())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/deliver")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("delivered")); // lowercase
    }
}
