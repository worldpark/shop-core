package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import com.shop.shop.payment.repository.PaymentRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 주문 이행 화면(templates/admin/orders.html) Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/admin/orders.html)이 레이아웃·프래그먼트와 함께
 * 올바르게 렌더링되는지 검증한다.
 *
 * <p>AdminOrderFulfillmentFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /admin/orders 200 렌더(목록·배송 현황)</li>
 *   <li>배송 생성 폼: paid+미발송 존재 시 노출, preparing+미발송 존재 시 노출</li>
 *   <li>배송 생성 폼: 미발송 0건/종결/pending 미노출</li>
 *   <li>CSRF 토큰 포함 확인</li>
 *   <li>POST /admin/orders/{id}/shipments 성공 → flashSuccess + redirect /admin/orders</li>
 *   <li>POST — OrderFulfillmentConflictException(409) → flashError + redirect</li>
 *   <li>비ADMIN 403, 비인증 /login redirect</li>
 * </ul>
 *
 * <p>패턴: AdminMembersRenderingTest @MockitoBean 목록 그대로 미러 (풀컨텍스트 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminOrderViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    // --- AdminMembersRenderingTest @MockitoBean 목록 그대로 미러 ---

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

    // --- 이 테스트 전용 --- (AdminMembersRenderingTest에는 없음)

    @MockitoBean
    private AdminOrderFulfillmentFacade adminOrderFulfillmentFacade;

    // ============================================================
    // 헬퍼 데이터
    // ============================================================

    /** paid 주문 + 미발송 항목 2건 + 배송 없음 */
    private AdminOrderFulfillmentView paidOrderWithUnshipped() {
        return new AdminOrderFulfillmentView(
                1L,
                "ORD-TEST-0001",
                "paid",
                List.of(
                        new AdminOrderFulfillmentView.UnshippedItem(101L, "티셔츠", 2),
                        new AdminOrderFulfillmentView.UnshippedItem(102L, "바지", 1)
                ),
                List.of()
        );
    }

    /** preparing 주문 + 미발송 항목 1건 + 기존 배송 1건 */
    private AdminOrderFulfillmentView preparingOrderWithUnshipped() {
        ShipmentItemResponse si = new ShipmentItemResponse(101L, "티셔츠", 2);
        ShipmentResponse shipment = new ShipmentResponse(10L, 2L, "preparing", List.of(si));
        return new AdminOrderFulfillmentView(
                2L,
                "ORD-TEST-0002",
                "preparing",
                List.of(new AdminOrderFulfillmentView.UnshippedItem(103L, "신발", 1)),
                List.of(shipment)
        );
    }

    /** paid 주문 + 미발송 항목 없음 (폼 미노출) */
    private AdminOrderFulfillmentView paidOrderNoUnshipped() {
        return new AdminOrderFulfillmentView(
                3L,
                "ORD-TEST-0003",
                "paid",
                List.of(),
                List.of()
        );
    }

    /** pending 주문 (폼 미노출) */
    private AdminOrderFulfillmentView pendingOrder() {
        return new AdminOrderFulfillmentView(
                4L,
                "ORD-TEST-0004",
                "pending",
                List.of(new AdminOrderFulfillmentView.UnshippedItem(104L, "모자", 1)),
                List.of()
        );
    }

    /** cancelled 주문 (종결, 폼 미노출) */
    private AdminOrderFulfillmentView cancelledOrder() {
        return new AdminOrderFulfillmentView(
                5L,
                "ORD-TEST-0005",
                "cancelled",
                List.of(new AdminOrderFulfillmentView.UnshippedItem(105L, "양말", 3)),
                List.of()
        );
    }

    @BeforeEach
    void setUp() {
        // 기본 stub: paid 주문 + 미발송 항목 포함 페이지 반환
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(paidOrderWithUnshipped()), pageable, 1L));
    }

    // ============================================================
    // (A1) GET /admin/orders — 접근 제어
    // ============================================================

    @Test
    @DisplayName("(A1) GET /admin/orders — 비인증 → 302 /login redirect")
    void get_adminOrders_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("(A1) GET /admin/orders — 비ADMIN(CONSUMER) → 403")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void get_adminOrders_consumer_returns403() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("(A1) GET /admin/orders — ADMIN → 200")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_admin_returns200() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // (A2) GET /admin/orders — 목록 렌더링
    // ============================================================

    @Test
    @DisplayName("(A2) GET /admin/orders — 주문번호 렌더링")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_rendersOrderNumber() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("주문번호가 렌더링되어야 함").contains("ORD-TEST-0001");
    }

    @Test
    @DisplayName("(A2) GET /admin/orders — 배송 현황(기존 배송) 렌더링")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_rendersExistingShipments() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(preparingOrderWithUnshipped()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("기존 배송 ID가 렌더링되어야 함").contains("배송 #10");
        assertThat(body).as("배송 상태가 렌더링되어야 함").contains("preparing");
        assertThat(body).as("배송에 포함된 상품명이 렌더링되어야 함").contains("티셔츠");
    }

    @Test
    @DisplayName("(A2) GET /admin/orders — 빈 목록 안내 렌더링")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_emptyList_showsEmptyMessage() throws Exception {
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("빈 목록 안내 메시지가 있어야 함").contains("이행 대상 주문이 없습니다");
    }

    // ============================================================
    // (A3) 배송 생성 폼 노출 조건
    // ============================================================

    @Test
    @DisplayName("(A3) paid 주문 + 미발송 항목 존재 → 배송 생성 폼 노출 + CSRF 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_paidWithUnshipped_showsShipmentForm() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 배송 생성 폼 노출
        assertThat(body).as("배송 생성 폼 action이 포함되어야 함")
                .contains("/admin/orders/1/shipments");

        // 미발송 항목 체크박스
        assertThat(body).as("미발송 항목 체크박스(orderItemIds)가 있어야 함")
                .contains("name=\"orderItemIds\"");

        // CSRF 토큰 자동 주입 (th:action 사용)
        assertThat(body).as("배송 생성 POST 폼에 _csrf 토큰이 자동 주입되어야 함")
                .contains("_csrf");

        // 미발송 상품명
        assertThat(body).as("미발송 항목 상품명이 렌더링되어야 함").contains("티셔츠");
        assertThat(body).as("미발송 항목 상품명(바지)이 렌더링되어야 함").contains("바지");
    }

    @Test
    @DisplayName("(A3) preparing 주문 + 미발송 항목 존재 → 배송 생성 폼 노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_preparingWithUnshipped_showsShipmentForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(preparingOrderWithUnshipped()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("preparing 주문 배송 생성 폼 action이 포함되어야 함")
                .contains("/admin/orders/2/shipments");
        assertThat(body).as("미발송 항목(신발) 체크박스가 있어야 함").contains("신발");
    }

    @Test
    @DisplayName("(A3) paid 주문 + 미발송 항목 0건 → 배송 생성 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_paidNoUnshipped_hidesShipmentForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(paidOrderNoUnshipped()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("미발송 0건 주문에는 배송 생성 폼이 없어야 함")
                .doesNotContain("/admin/orders/3/shipments");
        assertThat(body).as("미발송 0건 주문에는 orderItemIds 체크박스가 없어야 함")
                .doesNotContain("name=\"orderItemIds\"");
    }

    @Test
    @DisplayName("(A3) pending 주문 → 배송 생성 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_pendingOrder_hidesShipmentForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(pendingOrder()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("pending 주문에는 배송 생성 폼이 없어야 함")
                .doesNotContain("/admin/orders/4/shipments");
    }

    @Test
    @DisplayName("(A3) 종결(cancelled) 주문 → 배송 생성 폼 미노출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_cancelledOrder_hidesShipmentForm() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(adminOrderFulfillmentFacade.listFulfillableOrders(any()))
                .thenReturn(new PageImpl<>(List.of(cancelledOrder()), pageable, 1L));

        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("종결 주문에는 배송 생성 폼이 없어야 함")
                .doesNotContain("/admin/orders/5/shipments");
    }

    // ============================================================
    // (A4) POST /admin/orders/{id}/shipments — 배송 생성
    // ============================================================

    @Test
    @DisplayName("(A4) POST /admin/orders/{id}/shipments — 비인증 → 302 /login redirect")
    void post_createShipment_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/orders/1/shipments")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("(A4) POST /admin/orders/{id}/shipments — 비ADMIN(CONSUMER) → 403")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void post_createShipment_consumer_returns403() throws Exception {
        mockMvc.perform(post("/admin/orders/1/shipments")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("(A4) POST /admin/orders/{id}/shipments — 성공 → flashSuccess + redirect:/admin/orders")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_createShipment_success_flashSuccessAndRedirect() throws Exception {
        ShipmentItemResponse si = new ShipmentItemResponse(101L, "티셔츠", 2);
        ShipmentResponse response = new ShipmentResponse(10L, 1L, "preparing", List.of(si));
        when(adminOrderFulfillmentFacade.createShipment(anyLong(), anyList()))
                .thenReturn(response);

        mockMvc.perform(post("/admin/orders/1/shipments")
                        .with(csrf())
                        .param("orderItemIds", "101", "102"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("flashSuccess", "배송이 생성되었습니다."));
    }

    @Test
    @DisplayName("(A4) POST /admin/orders/{id}/shipments — orderItemIds 미전달(미발송 전부) → 성공 redirect")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_createShipment_noItemIds_success_redirect() throws Exception {
        ShipmentItemResponse si = new ShipmentItemResponse(101L, "티셔츠", 2);
        ShipmentResponse response = new ShipmentResponse(10L, 1L, "preparing", List.of(si));
        when(adminOrderFulfillmentFacade.createShipment(anyLong(), any()))
                .thenReturn(response);

        mockMvc.perform(post("/admin/orders/1/shipments")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("flashSuccess", "배송이 생성되었습니다."));
    }

    @Test
    @DisplayName("(A4) POST /admin/orders/{id}/shipments — OrderFulfillmentConflictException(409) → flashError + redirect:/admin/orders")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_createShipment_conflictException_flashErrorAndRedirect() throws Exception {
        when(adminOrderFulfillmentFacade.createShipment(anyLong(), any()))
                .thenThrow(new OrderFulfillmentConflictException("미발송 항목이 없어 배송을 생성할 수 없습니다."));

        mockMvc.perform(post("/admin/orders/1/shipments")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // (A5) 레이아웃 마커 (LayoutRenderingTest 비파괴)
    // ============================================================

    @Test
    @DisplayName("(A5) GET /admin/orders — CSS 링크(/css/app.css) 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_containsCssLink() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("/css/app.css 링크가 있어야 함 (layout/base 연계)")
                .contains("/css/app.css");
    }

    @Test
    @DisplayName("(A5) GET /admin/orders — footer 마커 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_containsFooterMarker() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("footer 마커가 있어야 함").contains("2026 shop-core. All rights reserved.");
    }

    @Test
    @DisplayName("(A5) GET /admin/orders — nav 홈 마커 포함")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_adminOrders_containsNavHomeMarker() throws Exception {
        String body = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav '홈' 마커가 있어야 함").contains("홈");
    }
}
