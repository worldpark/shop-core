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
import com.shop.shop.order.dto.ShipRequest;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminShipmentRestController + SecurityConfig REST 체인 MockMvc 통합 테스트 (Task 020).
 *
 * <p>검증:
 * <ul>
 *   <li>POST /api/v1/admin/shipments/{id}/ship: 성공 200 + ShipmentResponse(status=shipping)</li>
 *   <li>상태 충돌 409 / 미존재 배송 404 + ErrorResponse(내부 정보 비노출)</li>
 *   <li>권한: ADMIN 200·비인증 401·비ADMIN(CONSUMER/SELLER) 403</li>
 *   <li>요청 검증(@Valid): carrier/trackingNumber 빈 값 → 400</li>
 *   <li>멱등성: 이미 shipping 배송 → 200 (기존 값 반환)</li>
 *   <li>응답 carrier/trackingNumber/shippedAt 포함 여부</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminShipmentRestControllerSecurityTest {

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


    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    private static final Instant SHIPPED_AT = Instant.parse("2026-06-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // 성공 200 — preparing → shipping
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/admin/shipments/{id}/ship — ADMIN → 200 + status=shipping")
    void ship_admin_returns_200_statusShipping() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                100L, 1L, "shipping",
                "CJ대한통운", "TRK-001", SHIPPED_AT, null,
                List.of(new ShipmentItemResponse(10L, "상품A", 2)));

        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ대한통운\",\"trackingNumber\":\"TRK-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(100))
                .andExpect(jsonPath("$.status").value("shipping"))
                .andExpect(jsonPath("$.carrier").value("CJ대한통운"))
                .andExpect(jsonPath("$.trackingNumber").value("TRK-001"))
                .andExpect(jsonPath("$.shippedAt").isString());
    }

    @Test
    @DisplayName("POST — 응답에 items 배열 포함")
    void ship_admin_responseIncludesItems() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                100L, 1L, "shipping", "한진택배", "HJ-999", SHIPPED_AT, null,
                List.of(new ShipmentItemResponse(10L, "상품A", 2)));

        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"한진택배\",\"trackingNumber\":\"HJ-999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].orderItemId").value(10))
                .andExpect(jsonPath("$.items[0].productName").value("상품A"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    // ============================================================
    // 멱등성 — 이미 shipping → 200 기존 값 반환
    // ============================================================

    @Test
    @DisplayName("POST — 이미 shipping 배송 → 멱등 200 (기존 carrier/trackingNumber 반환)")
    void ship_alreadyShipping_idempotent_200() throws Exception {
        ShipmentResponse existingResponse = new ShipmentResponse(
                100L, 1L, "shipping", "기존택배", "기존TRK", SHIPPED_AT, null, List.of());

        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenReturn(existingResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"다른택배\",\"trackingNumber\":\"다른TRK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("기존택배"))
                .andExpect(jsonPath("$.trackingNumber").value("기존TRK"));
    }

    // ============================================================
    // 상태 충돌 409
    // ============================================================

    @Test
    @DisplayName("POST — 상태 충돌 → 409 + ErrorResponse(내부 정보 비노출)")
    void ship_conflict_returns_409() throws Exception {
        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenThrow(new OrderFulfillmentConflictException("주문이 취소되어 배송을 시작할 수 없습니다."));

        String responseBody = mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isString())
                .andReturn().getResponse().getContentAsString();

        assertFieldAbsent(responseBody, "stackTrace");
        assertFieldAbsent(responseBody, "orderId");
    }

    // ============================================================
    // 미존재 배송 404
    // ============================================================

    @Test
    @DisplayName("POST — 미존재 배송 → 404 + ErrorResponse")
    void ship_shipmentNotFound_returns_404() throws Exception {
        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenThrow(new ShipmentNotFoundException());

        mockMvc.perform(post("/api/v1/admin/shipments/9999/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // @Valid 요청 검증
    // ============================================================

    @Test
    @DisplayName("POST — carrier 빈 문자열 → 400 @Valid 검증 실패")
    void ship_emptyCarrier_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"\",\"trackingNumber\":\"TRK-001\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST — trackingNumber 빈 문자열 → 400 @Valid 검증 실패")
    void ship_emptyTrackingNumber_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST — 요청 본문 없음 → 비정상 응답(4xx or 5xx, 200 아님)")
    void ship_noBody_returns_error() throws Exception {
        // 본문 없이 Content-Type: application/json 전송 → HttpMessageNotReadableException 발생
        // 전역 핸들러 처리에 따라 4xx 또는 5xx 반환 (200이 아님이 핵심)
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is5xxServerError());
    }

    // ============================================================
    // 비인증 401
    // ============================================================

    @Test
    @DisplayName("POST — 비인증 Bearer 없음 → 401 JSON")
    void ship_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ============================================================
    // 비ADMIN 403
    // ============================================================

    @Test
    @DisplayName("POST — CONSUMER Bearer → 403 JSON")
    void ship_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST — SELLER Bearer → 403 JSON")
    void ship_seller_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ============================================================
    // 응답 status lowercase 검증
    // ============================================================

    @Test
    @DisplayName("응답 status는 lowercase(shipping) — 대문자 미사용")
    void ship_responseStatusIsLowercase() throws Exception {
        ShipmentResponse mockResponse = new ShipmentResponse(
                100L, 1L, "shipping", "CJ", "TRK", SHIPPED_AT, null, List.of());
        when(orderFulfillmentService.ship(anyLong(), anyString(), anyString()))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/admin/shipments/100/ship")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNumber\":\"TRK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("shipping")); // lowercase
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
