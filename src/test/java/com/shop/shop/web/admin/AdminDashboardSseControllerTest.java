package com.shop.shop.web.admin;

import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.order.spi.AdminOrderStatsFacade;
import com.shop.shop.product.spi.AdminProductStatsFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import com.shop.shop.web.admin.dto.AdminDashboardView;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminDashboardSseController} 보안/엔드포인트 MockMvc 통합 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>인가: ADMIN GET → 200 + text/event-stream</li>
 *   <li>인가: 비ADMIN(CONSUMER) → 403</li>
 *   <li>인가: 비ADMIN(SELLER) → 403</li>
 *   <li>인가: 비인증 → /login redirect (302)</li>
 * </ul>
 *
 * <p>test 프로파일에서 {@code shop.admin.dashboard.sse.enabled=false}이므로
 * {@link AdminDashboardSseSchedulingConfig}는 미로드(스케줄 미발화).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminDashboardSseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminDashboardAssembler assembler;

    // 풀 컨텍스트 빈 그래프 충족용 mock — 3 SPI 구현체 의존
    @MockitoBean
    private AdminMemberFacade adminMemberFacade;

    @MockitoBean
    private AdminOrderStatsFacade adminOrderStatsFacade;

    @MockitoBean
    private AdminProductStatsFacade adminProductStatsFacade;

    @MockitoBean
    private com.shop.shop.member.repository.MemberRepository memberRepository;

    @MockitoBean
    private com.shop.shop.member.repository.SellerApplicationRepository sellerApplicationRepository;

    @MockitoBean
    private com.shop.shop.member.service.MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private com.shop.shop.product.repository.CategoryRepository categoryRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ProductRepository productRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ProductOptionRepository productOptionRepository;

    @MockitoBean
    private com.shop.shop.product.repository.OptionValueRepository optionValueRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ProductVariantRepository productVariantRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ProductImageRepository productImageRepository;

    @MockitoBean
    private com.shop.shop.cart.repository.CartRepository cartRepository;

    @MockitoBean
    private com.shop.shop.cart.repository.CartItemRepository cartItemRepository;

    @MockitoBean
    private com.shop.shop.inventory.repository.InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private com.shop.shop.order.repository.OrderRepository orderRepository;

    @MockitoBean
    private com.shop.shop.order.repository.ShipmentRepository shipmentRepository;

    @MockitoBean
    private com.shop.shop.payment.repository.PaymentRepository paymentRepository;

    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private com.shop.shop.order.adapter.OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ReviewRepository reviewRepository;

    @Autowired
    private AdminDashboardSseRegistry registry;

    private static final String ADMIN_EMAIL = "admin@example.com";

    @BeforeEach
    void setUp() {
        AdminDashboardView.Metric zeroMetric = new AdminDashboardView.Metric(new BigDecimal("0.0"), 0L, 1L);
        AdminDashboardView stubView = new AdminDashboardView(zeroMetric, zeroMetric, zeroMetric, "최근 30일");
        when(assembler.build()).thenReturn(stubView);
    }

    // ============================================================
    // 인가 테스트
    // ============================================================

    @Test
    @DisplayName("GET /admin/dashboard/stream — ADMIN → async 시작 + Content-Type: text/event-stream")
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    void stream_admin_returns_async_started_with_event_stream_content_type() throws Exception {
        // SSE는 장기 연결이라 asyncDispatch 없이 async 시작 확인 후 응답 헤더만 검증한다.
        // SseEmitter 반환 시 Spring MVC는 즉시 헤더(Content-Type: text/event-stream)를 설정한다.
        MvcResult result = mockMvc.perform(get("/admin/dashboard/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 비동기 시작 직후 응답 헤더에서 Content-Type 확인(asyncDispatch 불필요)
        String contentType = result.getResponse().getContentType();
        assertThat(contentType).contains("text/event-stream");
    }

    @Test
    @DisplayName("GET /admin/dashboard/stream — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void stream_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/admin/dashboard/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/dashboard/stream — SELLER → 403")
    @WithMockUser(username = "seller@example.com", roles = "SELLER")
    void stream_seller_returns_403() throws Exception {
        mockMvc.perform(get("/admin/dashboard/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/dashboard/stream — 비인증 → /login redirect (302)")
    void stream_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/dashboard/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    // ============================================================
    // 방어적 코딩 보강 — build RuntimeException 시 레지스트리 누수 없음
    // ============================================================

    @Test
    @DisplayName("assembler.build() RuntimeException → 레지스트리에 dead emitter 남지 않음")
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    void stream_build_runtime_exception_does_not_leak_emitter_into_registry() {
        // assembler.build()가 DataAccessException(RuntimeException 계열)을 던지도록 스텁
        when(assembler.build()).thenThrow(new QueryTimeoutException("DB timeout"));

        int countBefore = registry.activeCount();

        // MockMvc는 컨트롤러의 미처리 RuntimeException을 ServletException으로 래핑해 throw한다.
        // perform() 자체가 예외를 던지는 것이 정상 동작이며, 여기서 핵심은 레지스트리 누수 없음이다.
        assertThatThrownBy(() ->
                mockMvc.perform(get("/admin/dashboard/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
        ).isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);

        // build 실패 시 registry.add()가 호출되지 않으므로 emitter 누수 없음
        assertThat(registry.activeCount()).isEqualTo(countBefore);
    }

    // ============================================================
    // 스케줄 비발화 확인(test 프로파일)
    // ============================================================

    @Test
    @DisplayName("test 프로파일 — AdminDashboardSseSchedulingConfig 빈 미존재(SSE 스케줄 비로드)")
    void test_profile_scheduling_config_not_loaded(
            @Autowired org.springframework.context.ApplicationContext context) {
        // shop.admin.dashboard.sse.enabled=false → AdminDashboardSseSchedulingConfig 미로드
        boolean exists = context.getBeanNamesForType(AdminDashboardSseSchedulingConfig.class).length > 0;
        assertThat(exists).isFalse();
    }
}
