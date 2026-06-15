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
 * 관리자 배송 시작 화면(POST /admin/shipments/{id}/ship, templates/admin/orders.html 배송 시작 폼)
 * Thymeleaf 렌더링 통합 테스트 (020).
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증한다.
 * AdminOrderFulfillmentFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(S1) GET /admin/orders — preparing 배송에 배송 시작 폼 노출 + CSRF 포함</li>
 *   <li>(S2) GET /admin/orders — shipping 배송에 택배사·운송장·배송시작시각 표시 + 배송 시작 폼 미노출</li>
 *   <li>(S3) POST /admin/shipments/{id}/ship — 성공 → flashSuccess + redirect:/admin/orders</li>
 *   <li>(S4) POST /admin/shipments/{id}/ship — 불가(409 OrderFulfillmentConflictException) → flashError + redirect</li>
 *   <li>(S5) POST /admin/shipments/{id}/ship — 미존재 배송(ShipmentNotFoundException 404) → flashError + redirect</li>
 *   <li>(S6) POST /admin/shipments/{id}/ship — 비인증 → 302 /login redirect</li>
 *   <li>(S7) POST /admin/shipments/{id}/ship — 비ADMIN(CONSUMER) → 403</li>
 * </ul>
 *
 * <p>패턴: AdminOrderViewRenderingTest @MockitoBean 목록 그대로 미러.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminShipViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    // --- AdminOrderViewRenderingTest @MockitoBean 목록 그대로 미러 ---

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

    /** preparing 주문 + preparing 배송 1건 (배송 시작 폼 노출 대상) */
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

    /** shipping 주문 + shipping 배송 1건 (추적정보 표시, 폼 미노출) */
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

    @BeforeEach
    void setUp() {
        // 기본 stub: preparing 배송 포함 주문 반환
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(preparingOrderWithPreparingShipment()), pageable, 1L));
    }

    // ============================================================
    // (S1) GET /admin/orders — preparing 배송 시작 폼 노출
    // ============================================================

    @Test
    @DisplayName("(S1) GET /admin/orders — preparing 배송에 배송 시작 폼 노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_preparingShipment_showsShipForm() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 배송 시작 폼 action 포함 (절대경로 /admin/shipments/{id}/ship)
        assertThat(body).as("배송 시작 폼 action이 포함되어야 함")
                .contains("/admin/shipments/20/ship");

        // 택배사 입력 필드
        assertThat(body).as("carrier 입력 필드가 있어야 함")
                .contains("name=\"carrier\"");

        // 운송장번호 입력 필드
        assertThat(body).as("trackingNumber 입력 필드가 있어야 함")
                .contains("name=\"trackingNumber\"");

        // 배송 시작 버튼
        assertThat(body).as("배송 시작 버튼이 있어야 함")
                .contains("배송 시작");
    }

    @Test
    @DisplayName("(S1) GET /admin/orders — preparing 배송 시작 폼에 CSRF 토큰 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_preparingShipment_formContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:action 사용 시 _csrf hidden 자동 주입
        assertThat(body).as("배송 시작 폼에 _csrf 토큰이 자동 주입되어야 함")
                .contains("_csrf");
    }

    // ============================================================
    // (S2) GET /admin/orders — shipping 배송 추적정보 표시, 폼 미노출
    // ============================================================

    @Test
    @DisplayName("(S2) GET /admin/orders — shipping 배송에 택배사·운송장 표시")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_shippingShipment_showsTrackingInfo() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(shippingOrderWithShippingShipment()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("택배사명이 렌더링되어야 함").contains("CJ대한통운");
        assertThat(body).as("운송장번호가 렌더링되어야 함").contains("1234567890");
        // 배송 상태 라벨
        assertThat(body).as("shipping 상태 라벨이 렌더링되어야 함").contains("배송 중");
    }

    @Test
    @DisplayName("(S2) GET /admin/orders — shipping 배송에는 배송 시작 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_shippingShipment_hidesShipForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(shippingOrderWithShippingShipment()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("shipping 배송에는 배송 시작 폼이 없어야 함")
                .doesNotContain("/admin/shipments/21/ship");
    }

    // ============================================================
    // (S3) POST /admin/shipments/{id}/ship — 성공
    // ============================================================

    @Test
    @DisplayName("(S3) POST /admin/shipments/{id}/ship — 성공 → flashSuccess + redirect:/admin/orders")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_ship_success_flashSuccessAndRedirect() throws Exception {
        ShipmentItemResponse si = new ShipmentItemResponse(101L, "티셔츠", 2);
        ShipmentResponse response = new ShipmentResponse(
                20L, 1L, "shipping",
                "CJ대한통운", "1234567890",
                Instant.parse("2026-06-11T10:00:00Z"), null,
                List.of(si));
        when(adminOrderFulfillmentFacade.ship(anyLong(), anyString(), anyString()))
                .thenReturn(response);

        mockMvc.perform(post("/admin/shipments/20/ship")
                        .with(csrf())
                        .param("carrier", "CJ대한통운")
                        .param("trackingNumber", "1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("flashSuccess", "배송이 시작되었습니다."));
    }

    // ============================================================
    // (S4) POST /admin/shipments/{id}/ship — 409 상태 충돌
    // ============================================================

    @Test
    @DisplayName("(S4) POST /admin/shipments/{id}/ship — OrderFulfillmentConflictException(409) → flashError + redirect")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_ship_conflictException_flashErrorAndRedirect() throws Exception {
        when(adminOrderFulfillmentFacade.ship(anyLong(), anyString(), anyString()))
                .thenThrow(new OrderFulfillmentConflictException("배송 상태 충돌로 배송을 시작할 수 없습니다."));

        mockMvc.perform(post("/admin/shipments/20/ship")
                        .with(csrf())
                        .param("carrier", "CJ대한통운")
                        .param("trackingNumber", "1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (S5) POST /admin/shipments/{id}/ship — 미존재 배송 404
    // ============================================================

    @Test
    @DisplayName("(S5) POST /admin/shipments/{id}/ship — ShipmentNotFoundException(404) → flashError + redirect")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_ship_notFound_flashErrorAndRedirect() throws Exception {
        when(adminOrderFulfillmentFacade.ship(anyLong(), anyString(), anyString()))
                .thenThrow(new ShipmentNotFoundException());

        mockMvc.perform(post("/admin/shipments/999/ship")
                        .with(csrf())
                        .param("carrier", "CJ대한통운")
                        .param("trackingNumber", "9999999999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (S6) POST /admin/shipments/{id}/ship — 비인증 → /login redirect
    // ============================================================

    @Test
    @DisplayName("(S6) POST /admin/shipments/{id}/ship — 비인증 → 302 /login redirect")
    void post_ship_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/shipments/20/ship")
                        .with(csrf())
                        .param("carrier", "CJ대한통운")
                        .param("trackingNumber", "1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // (S7) POST /admin/shipments/{id}/ship — 비ADMIN → 403
    // ============================================================

    @Test
    @DisplayName("(S7) POST /admin/shipments/{id}/ship — 비ADMIN(CONSUMER) → 403")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void post_ship_consumer_returns403() throws Exception {
        mockMvc.perform(post("/admin/shipments/20/ship")
                        .with(csrf())
                        .param("carrier", "CJ대한통운")
                        .param("trackingNumber", "1234567890"))
                .andExpect(status().isForbidden());
    }
}
