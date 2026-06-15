package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 배송 완료 화면(POST /admin/shipments/{id}/deliver, templates/admin/orders.html 배송 완료 폼)
 * Thymeleaf 렌더링 통합 테스트 (021).
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증한다.
 * AdminOrderFulfillmentFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(D1) GET /admin/orders — shipping 배송에 배송 완료 폼 노출 + CSRF 포함</li>
 *   <li>(D2) GET /admin/orders — delivered 배송에 배송완료시각 표시, 완료 폼 미노출</li>
 *   <li>(D3) GET /admin/orders — preparing 배송에는 배송 완료 폼 미노출</li>
 *   <li>(D4) POST /admin/shipments/{id}/deliver — 성공 → flashSuccess + redirect:/admin/orders</li>
 *   <li>(D5) POST /admin/shipments/{id}/deliver — 불가(409 OrderFulfillmentConflictException) → flashError + redirect</li>
 *   <li>(D6) POST /admin/shipments/{id}/deliver — 미존재 배송(ShipmentNotFoundException 404) → flashError + redirect</li>
 *   <li>(D7) POST /admin/shipments/{id}/deliver — 비인증 → 302 /login redirect</li>
 *   <li>(D8) POST /admin/shipments/{id}/deliver — 비ADMIN(CONSUMER) → 403</li>
 * </ul>
 *
 * <p>패턴: AdminShipViewRenderingTest @MockitoBean 목록 그대로 미러.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminDeliverViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    // --- AdminShipViewRenderingTest @MockitoBean 목록 그대로 미러 ---

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
    private AdminOrderFulfillmentFacade adminOrderFulfillmentFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    // ============================================================
    // 헬퍼 데이터
    // ============================================================

    /** shipping 주문 + shipping 배송 1건 (배송 완료 폼 노출 대상) */
    private AdminOrderFulfillmentView shippingOrderWithShippingShipment() {
        ShipmentItemResponse si = new ShipmentItemResponse(102L, "바지", 1);
        ShipmentResponse shipment = new ShipmentResponse(
                21L, 2L, "shipping",
                "CJ대한통운", "1234567890",
                Instant.parse("2026-06-11T10:00:00Z"), null,
                List.of(si));
        return new AdminOrderFulfillmentView(
                2L,
                "ORD-TEST-0021",
                "shipping",
                List.of(),
                List.of(shipment)
        );
    }

    /** delivered 주문 + delivered 배송 1건 (배송완료시각 표시 대상) */
    private AdminOrderFulfillmentView deliveredOrderWithDeliveredShipment() {
        ShipmentItemResponse si = new ShipmentItemResponse(103L, "모자", 1);
        // deliveredAt: 2026-06-12T05:30:00Z → Asia/Seoul KST = 2026-06-12 14:30:00
        ShipmentResponse shipment = new ShipmentResponse(
                22L, 3L, "delivered",
                "CJ대한통운", "9876543210",
                Instant.parse("2026-06-11T10:00:00Z"),
                Instant.parse("2026-06-12T05:30:00Z"),
                List.of(si));
        return new AdminOrderFulfillmentView(
                3L,
                "ORD-TEST-0022",
                "delivered",
                List.of(),
                List.of(shipment)
        );
    }

    /** preparing 주문 + preparing 배송 1건 (배송 완료 폼 미노출) */
    private AdminOrderFulfillmentView preparingOrderWithPreparingShipment() {
        ShipmentItemResponse si = new ShipmentItemResponse(101L, "티셔츠", 2);
        ShipmentResponse shipment = new ShipmentResponse(
                20L, 1L, "preparing", null, null, null, null, List.of(si));
        return new AdminOrderFulfillmentView(
                1L,
                "ORD-TEST-0020",
                "preparing",
                List.of(),
                List.of(shipment)
        );
    }

    @BeforeEach
    void setUp() {
        // 기본 stub: shipping 배송 포함 주문 반환
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(shippingOrderWithShippingShipment()), pageable, 1L));
    }

    // ============================================================
    // (D1) GET /admin/orders — shipping 배송에 배송 완료 폼 노출 + CSRF 포함
    // ============================================================

    @Test
    @DisplayName("(D1) GET /admin/orders — shipping 배송에 배송 완료 폼 노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_shippingShipment_showsDeliverForm() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 배송 완료 폼 action 포함 (절대경로 /admin/shipments/{id}/deliver)
        assertThat(body).as("배송 완료 폼 action이 포함되어야 함")
                .contains("/admin/shipments/21/deliver");

        // 배송 완료 버튼
        assertThat(body).as("배송 완료 버튼이 있어야 함")
                .contains("배송 완료");
    }

    @Test
    @DisplayName("(D1) GET /admin/orders — shipping 배송 완료 폼에 CSRF 토큰 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_shippingShipment_deliverFormContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:action 사용 시 _csrf hidden 자동 주입
        assertThat(body).as("배송 완료 폼에 _csrf 토큰이 자동 주입되어야 함")
                .contains("_csrf");
    }

    // ============================================================
    // (D2) GET /admin/orders — delivered 배송에 배송완료시각 표시, 완료 폼 미노출
    // ============================================================

    @Test
    @DisplayName("(D2) GET /admin/orders — delivered 배송에 배송완료시각(Asia/Seoul) 표시")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_deliveredShipment_showsDeliveredAt() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(deliveredOrderWithDeliveredShipment()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // deliveredAt: 2026-06-12T05:30:00Z → Asia/Seoul KST = 2026-06-12 14:30:00
        assertThat(body).as("배송완료시각이 Asia/Seoul KST로 변환되어 렌더링되어야 함")
                .contains("2026-06-12 14:30:00");

        // 배송 완료 상태 라벨
        assertThat(body).as("delivered 상태 라벨 '배송 완료'가 렌더링되어야 함")
                .contains("배송 완료");
    }

    @Test
    @DisplayName("(D2) GET /admin/orders — delivered 배송에는 배송 완료 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_deliveredShipment_hidesDeliverForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(deliveredOrderWithDeliveredShipment()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // delivered 배송의 deliver 폼 미노출 (delivered가 아닌 배송 action 없어야 함)
        assertThat(body).as("delivered 배송에는 배송 완료 폼(deliver action)이 없어야 함")
                .doesNotContain("/admin/shipments/22/deliver");
    }

    // ============================================================
    // (D3) GET /admin/orders — preparing 배송에는 배송 완료 폼 미노출
    // ============================================================

    @Test
    @DisplayName("(D3) GET /admin/orders — preparing 배송에는 배송 완료 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_preparingShipment_hidesDeliverForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(preparingOrderWithPreparingShipment()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("preparing 배송에는 배송 완료 폼이 없어야 함")
                .doesNotContain("/admin/shipments/20/deliver");
    }

    // ============================================================
    // (D4) POST /admin/shipments/{id}/deliver — 성공
    // ============================================================

    @Test
    @DisplayName("(D4) POST /admin/shipments/{id}/deliver — 성공 → flashSuccess + redirect:/admin/orders")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_deliver_success_flashSuccessAndRedirect() throws Exception {
        ShipmentItemResponse si = new ShipmentItemResponse(102L, "바지", 1);
        ShipmentResponse shipment = new ShipmentResponse(
                21L, 2L, "delivered",
                "CJ대한통운", "1234567890",
                Instant.parse("2026-06-11T10:00:00Z"),
                Instant.parse("2026-06-12T05:30:00Z"),
                List.of(si));
        DeliverResponse response = new DeliverResponse(shipment, true);
        when(adminOrderFulfillmentFacade.deliver(anyLong()))
                .thenReturn(response);

        mockMvc.perform(post("/admin/shipments/21/deliver")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("flashSuccess", "배송이 완료 처리되었습니다."));
    }

    // ============================================================
    // (D5) POST /admin/shipments/{id}/deliver — 409 상태 충돌
    // ============================================================

    @Test
    @DisplayName("(D5) POST /admin/shipments/{id}/deliver — OrderFulfillmentConflictException(409) → flashError + redirect")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_deliver_conflictException_flashErrorAndRedirect() throws Exception {
        when(adminOrderFulfillmentFacade.deliver(anyLong()))
                .thenThrow(new OrderFulfillmentConflictException("배송 상태 충돌로 완료 처리할 수 없습니다."));

        mockMvc.perform(post("/admin/shipments/21/deliver")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (D6) POST /admin/shipments/{id}/deliver — 미존재 배송 404
    // ============================================================

    @Test
    @DisplayName("(D6) POST /admin/shipments/{id}/deliver — ShipmentNotFoundException(404) → flashError + redirect")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_deliver_notFound_flashErrorAndRedirect() throws Exception {
        when(adminOrderFulfillmentFacade.deliver(anyLong()))
                .thenThrow(new ShipmentNotFoundException());

        mockMvc.perform(post("/admin/shipments/999/deliver")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (D7) POST /admin/shipments/{id}/deliver — 비인증 → /login redirect
    // ============================================================

    @Test
    @DisplayName("(D7) POST /admin/shipments/{id}/deliver — 비인증 → 302 /login redirect")
    void post_deliver_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/shipments/21/deliver")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // (D8) POST /admin/shipments/{id}/deliver — 비ADMIN → 403
    // ============================================================

    @Test
    @DisplayName("(D8) POST /admin/shipments/{id}/deliver — 비ADMIN(CONSUMER) → 403")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void post_deliver_consumer_returns403() throws Exception {
        mockMvc.perform(post("/admin/shipments/21/deliver")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
