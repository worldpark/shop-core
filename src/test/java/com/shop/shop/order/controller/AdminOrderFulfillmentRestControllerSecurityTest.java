package com.shop.shop.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InvalidShipmentItemException;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.CreateShipmentRequest;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminOrderFulfillmentRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/admin/orders/{id}/shipments: 성공 201 + ShipmentResponse(status=preparing·items)</li>
 *   <li>상태 충돌 409 / 입력 오류 400 / 미존재 404 + ErrorResponse(내부정보 비노출)</li>
 *   <li>GET /api/v1/admin/orders/{id}/shipments: 200 배송 목록</li>
 *   <li>권한: ADMIN 201/200·비인증 401·비ADMIN(CONSUMER/SELLER) 403</li>
 *   <li>응답 status lowercase(preparing)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminOrderFulfillmentRestControllerSecurityTest {

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

    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // POST /api/v1/admin/orders/{id}/shipments — 성공 201
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/admin/orders/{id}/shipments — ADMIN → 201 + ShipmentResponse(status=preparing)")
    void create_admin_returns_201() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                100L, 1L, "preparing",
                List.of(new ShipmentItemResponse(10L, "상품A", 2)));

        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[10]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipmentId").value(100))
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.status").value("preparing"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].orderItemId").value(10))
                .andExpect(jsonPath("$.items[0].productName").value("상품A"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("POST body 생략(null) → ADMIN 성공 201 (미발송 전부 배송 생성)")
    void create_noBody_admin_returns_201() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                101L, 1L, "preparing", List.of());

        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenReturn(mockResponse);
        // null orderItemIds도 처리
        when(orderFulfillmentService.createShipment(1L, null))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("preparing"));
    }

    // ============================================================
    // 상태 충돌 409
    // ============================================================

    @Test
    @DisplayName("POST → 상태 충돌(409) → 409 + ErrorResponse(내부 정보 비노출)")
    void create_conflict_returns_409() throws Exception {
        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenThrow(new OrderFulfillmentConflictException("주문 상태 충돌로 배송을 생성할 수 없습니다."));
        when(orderFulfillmentService.createShipment(anyLong(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new OrderFulfillmentConflictException("주문 상태 충돌로 배송을 생성할 수 없습니다."));

        String responseBody = mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[10]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString())
                .andReturn().getResponse().getContentAsString();

        // 내부 정보 비노출 (스택트레이스·Entity·ownerId 없음)
        assertFieldAbsent(responseBody, "stackTrace");
        assertFieldAbsent(responseBody, "ownerId");
    }

    // ============================================================
    // 입력 오류 400
    // ============================================================

    @Test
    @DisplayName("POST → 입력 오류(400, 미존재 orderItemId) → 400 + ErrorResponse")
    void create_invalidItem_returns_400() throws Exception {
        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenThrow(new InvalidShipmentItemException("지정한 배송 항목이 유효하지 않습니다."));

        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isString());
    }

    // ============================================================
    // 미존재 주문 404
    // ============================================================

    @Test
    @DisplayName("POST → 미존재 주문(404) → 404 + ErrorResponse")
    void create_orderNotFound_returns_404() throws Exception {
        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenThrow(new OrderNotFoundException());
        when(orderFulfillmentService.createShipment(anyLong(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(post("/api/v1/admin/orders/999/shipments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // GET /api/v1/admin/orders/{id}/shipments — 200
    // ============================================================

    @Test
    @DisplayName("GET /api/v1/admin/orders/{id}/shipments — ADMIN → 200 + 배송 목록")
    void list_admin_returns_200() throws Exception {
        when(orderFulfillmentService.getShipments(1L))
                .thenReturn(List.of(
                        new ShipmentResponse(100L, 1L, "preparing",
                                List.of(new ShipmentItemResponse(10L, "상품A", 1)))));

        mockMvc.perform(get("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].shipmentId").value(100))
                .andExpect(jsonPath("$[0].status").value("preparing"));
    }

    @Test
    @DisplayName("GET — 미존재 주문도 빈 목록 200 반환")
    void list_orderNotFound_returns_200_emptyList() throws Exception {
        when(orderFulfillmentService.getShipments(999L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/orders/999/shipments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ============================================================
    // 비인증 401
    // ============================================================

    @Test
    @DisplayName("POST — 비인증 Bearer 없음 → 401 JSON")
    void create_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[10]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET — 비인증 Bearer 없음 → 401 JSON")
    void list_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/1/shipments"))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // 비ADMIN 403
    // ============================================================

    @Test
    @DisplayName("POST — CONSUMER Bearer → 403 JSON")
    void create_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[10]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST — SELLER Bearer → 403 JSON")
    void create_seller_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\":[10]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET — CONSUMER Bearer → 403 JSON")
    void list_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // status lowercase 검증
    // ============================================================

    @Test
    @DisplayName("응답 status는 lowercase(preparing) — 대문자 미사용")
    void create_responseStatusIsLowercase() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                100L, 1L, "preparing", List.of());
        when(orderFulfillmentService.createShipment(anyLong(), anyList()))
                .thenReturn(mockResponse);
        when(orderFulfillmentService.createShipment(anyLong(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/orders/1/shipments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("preparing")); // lowercase
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void assertFieldAbsent(String jsonBody, String fieldName) {
        org.assertj.core.api.Assertions.assertThat(jsonBody)
                .as("응답 본문에 민감정보 필드 '%s'가 포함되어선 안 됩니다", fieldName)
                .doesNotContain("\"" + fieldName + "\"");
    }
}
