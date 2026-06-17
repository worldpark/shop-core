package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import com.shop.shop.product.dto.VariantProductMapping;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SellerSalesStatsSseController} 보안/엔드포인트 MockMvc 통합 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>인가: SELLER GET → 200 + text/event-stream</li>
 *   <li>인가: CONSUMER → 403 (비SELLER)</li>
 *   <li>인가: 비인증 → /login redirect (302)</li>
 *   <li>초기 스냅샷 직접 build 경로(broadcaster union 미경유)</li>
 *   <li>build RuntimeException → registry 누수 없음(dead emitter 차단)</li>
 *   <li>@Scheduled off 확인(test 프로파일에서 SellerSalesStatsSseSchedulingConfig 미로드)</li>
 * </ul>
 *
 * <p>test 프로파일에서 {@code shop.seller.sales.sse.enabled=false}이므로
 * {@link SellerSalesStatsSseSchedulingConfig}는 미로드(스케줄 미발화).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerSalesStatsSseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerProductFacade sellerProductFacade;

    @MockitoBean
    private SellerSalesStatsPort sellerSalesStatsPort;

    @MockitoBean
    private SellerProductStatsAssembler assembler;

    // 풀 컨텍스트 빈 그래프 충족용 mock
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
    private SellerSalesStatsSseRegistry registry;

    private static final String SELLER_EMAIL = "seller@example.com";

    @BeforeEach
    void setUp() {
        // 기본 stub: 빈 매핑, 빈 집계, 빈 스냅샷
        when(sellerProductFacade.getMyOwnedVariantMappings(anyString())).thenReturn(List.of());
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(List.of());
        when(assembler.buildSnapshot(any(), any())).thenReturn(
                new com.shop.shop.web.product.dto.SellerSalesSnapshot(java.util.Map.of())
        );
    }

    // ============================================================
    // 인가 테스트
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/stats/stream — SELLER → async 시작 + Content-Type: text/event-stream")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stream_seller_returns_async_started_with_event_stream_content_type() throws Exception {
        MvcResult result = mockMvc.perform(get("/seller/products/stats/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        String contentType = result.getResponse().getContentType();
        assertThat(contentType).contains("text/event-stream");
    }

    @Test
    @DisplayName("GET /seller/products/stats/stream — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void stream_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/seller/products/stats/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /seller/products/stats/stream — 비인증 → /login redirect (302)")
    void stream_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/seller/products/stats/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    // ============================================================
    // 초기 스냅샷 직접 build 경로 검증
    // ============================================================

    @Test
    @DisplayName("연결 시 getMyOwnedVariantMappings + aggregateByVariantIds + buildSnapshot 직접 호출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stream_calls_initial_snapshot_build_directly() throws Exception {
        List<VariantProductMapping> mappings = List.of(
                new VariantProductMapping(100L, 10L)
        );
        List<VariantSalesAggregate> aggregates = List.of(
                new VariantSalesAggregate(100L, 3L, new BigDecimal("30000.00"))
        );
        when(sellerProductFacade.getMyOwnedVariantMappings(SELLER_EMAIL)).thenReturn(mappings);
        when(sellerSalesStatsPort.aggregateByVariantIds(any())).thenReturn(aggregates);

        mockMvc.perform(get("/seller/products/stats/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        org.mockito.Mockito.verify(sellerProductFacade).getMyOwnedVariantMappings(SELLER_EMAIL);
        org.mockito.Mockito.verify(sellerSalesStatsPort).aggregateByVariantIds(any());
        org.mockito.Mockito.verify(assembler).buildSnapshot(any(), any());
    }

    // ============================================================
    // dead emitter 차단: build RuntimeException → 레지스트리 누수 없음
    // ============================================================

    @Test
    @DisplayName("getMyOwnedVariantMappings RuntimeException → 레지스트리에 dead emitter 남지 않음")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stream_build_runtime_exception_does_not_leak_emitter() {
        when(sellerProductFacade.getMyOwnedVariantMappings(anyString()))
                .thenThrow(new QueryTimeoutException("DB timeout"));

        int countBefore = registry.connectedCount();

        assertThatThrownBy(() ->
                mockMvc.perform(get("/seller/products/stats/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
        ).isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);

        // build 실패 시 registry.add() 미호출 → emitter 누수 없음
        assertThat(registry.connectedCount()).isEqualTo(countBefore);
    }

    // ============================================================
    // @Scheduled off 확인
    // ============================================================

    @Test
    @DisplayName("test 프로파일 — SellerSalesStatsSseSchedulingConfig 빈 미존재(SSE 스케줄 비로드)")
    void test_profile_scheduling_config_not_loaded(
            @Autowired org.springframework.context.ApplicationContext context) {
        // shop.seller.sales.sse.enabled=false → SellerSalesStatsSseSchedulingConfig 미로드
        boolean exists = context.getBeanNamesForType(SellerSalesStatsSseSchedulingConfig.class).length > 0;
        assertThat(exists).isFalse();
    }
}
