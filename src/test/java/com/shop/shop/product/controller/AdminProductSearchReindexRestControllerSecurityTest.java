package com.shop.shop.product.controller;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.dto.ReindexStatusResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.service.ProductSearchReindexServiceResponse;
import com.shop.shop.product.service.ReindexStatus;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminProductSearchReindexRestController + SecurityConfig REST 체인 MockMvc 보안 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>POST /reindex — ADMIN → 202, CONSUMER → 403, 비인증 → 401</li>
 *   <li>GET /status — ADMIN → 200, CONSUMER → 403, 비인증 → 401</li>
 *   <li>비동기 즉시 응답 — 트리거 호출 후 즉시 202 반환(완주 비대기)</li>
 *   <li>이미 실행 중(RUNNING) → 409 Conflict</li>
 * </ul>
 *
 * <p>ES 불요 — {@link ProductSearchReindexServiceResponse}를 @MockitoBean으로 교체.
 * 이 컨트롤러는 ES 의존 없이 항상 배선된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminProductSearchReindexRestControllerSecurityTest {

    private static final String REINDEX_URL = "/api/v1/admin/products/search-index/reindex";
    private static final String STATUS_URL = "/api/v1/admin/products/search-index/status";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductSearchReindexServiceResponse reindexServiceResponse;

    // 풀 컨텍스트 배선 충족용 Repository mocks (AdminMemberRestControllerSecurityTest 패턴 계승)
    @MockitoBean
    private MemberRepository memberRepository;
    @MockitoBean
    private SellerApplicationRepository sellerApplicationRepository;
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
    private CouponRepository couponRepository;
    @MockitoBean
    private UserCouponRepository userCouponRepository;
    @MockitoBean
    private ReviewRepository reviewRepository;
    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    private String adminToken;
    private String consumerToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        consumerToken = jwtTokenProvider.createAccess(2L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 stub
        ReindexStatusResponse runningStatus = ReindexStatusResponse.builder()
                .state("RUNNING")
                .startedAt(Instant.now())
                .processedCount(0L)
                .build();
        ReindexStatusResponse idleStatus = ReindexStatusResponse.builder()
                .state("IDLE")
                .processedCount(0L)
                .build();

        when(reindexServiceResponse.startReindex()).thenReturn(runningStatus);
        when(reindexServiceResponse.getStatus()).thenReturn(idleStatus);
    }

    // ========================================================================
    // POST /reindex
    // ========================================================================

    @Test
    @DisplayName("POST /reindex — ADMIN Bearer → 202 Accepted + state=RUNNING")
    void reindex_admin_returns_202() throws Exception {
        mockMvc.perform(post(REINDEX_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /reindex — CONSUMER Bearer → 403 Forbidden")
    void reindex_consumer_returns_403() throws Exception {
        mockMvc.perform(post(REINDEX_URL)
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reindex — 비인증(토큰 없음) → 401 Unauthorized")
    void reindex_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post(REINDEX_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /reindex — 이미 RUNNING 상태 → 409 Conflict")
    void reindex_alreadyRunning_returns_409() throws Exception {
        ReindexStatusResponse runningStatus = ReindexStatusResponse.builder()
                .state("RUNNING")
                .startedAt(Instant.now())
                .processedCount(100L)
                .build();
        when(reindexServiceResponse.startReindex())
                .thenThrow(new IllegalStateException("Already running"));
        when(reindexServiceResponse.getStatus()).thenReturn(runningStatus);

        mockMvc.perform(post(REINDEX_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /reindex — 비동기 즉시 응답(완주 비대기): 202 즉시 반환")
    void reindex_admin_asyncImmediateResponse() throws Exception {
        // mock이 즉시 반환하므로 202가 즉시 응답됨 검증
        // (실제 ES 작업 완주를 기다리지 않는다는 계약 검증)
        mockMvc.perform(post(REINDEX_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
    }

    // ========================================================================
    // GET /status
    // ========================================================================

    @Test
    @DisplayName("GET /status — ADMIN Bearer → 200 OK + state=IDLE")
    void status_admin_returns_200() throws Exception {
        mockMvc.perform(get(STATUS_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }

    @Test
    @DisplayName("GET /status — CONSUMER Bearer → 403 Forbidden")
    void status_consumer_returns_403() throws Exception {
        mockMvc.perform(get(STATUS_URL)
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /status — 비인증 → 401 Unauthorized")
    void status_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get(STATUS_URL))
                .andExpect(status().isUnauthorized());
    }
}
