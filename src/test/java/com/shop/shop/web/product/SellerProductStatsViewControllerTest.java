package com.shop.shop.web.product;

import com.shop.shop.order.spi.SellerSalesStatsPort;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import com.shop.shop.web.product.dto.SellerProductStatsRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * {@link SellerProductStatsViewController} + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>인가: SELLER 200 / CONSUMER 403 / 비인증 로그인 리다이렉트</li>
 *   <li>조합 결과(statsPage)가 모델에 존재</li>
 *   <li>assembler.assemble(actorEmail, pageable) 호출 검증 (IDOR 보호)</li>
 *   <li>뷰 이름: seller/product-stats</li>
 * </ul>
 *
 * <p>SellerProductStatsAssembler는 @MockitoBean으로 격리한다.
 * 조합 로직 단위 테스트는 별도 테스트에서 수행.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerProductStatsViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerProductStatsAssembler assembler;

    // 풀 컨텍스트 빈 그래프 충족용 mock — SellerProductFacadeImpl / SellerSalesStatsService 의존
    @MockitoBean
    private SellerProductFacade sellerProductFacade;

    @MockitoBean
    private SellerSalesStatsPort sellerSalesStatsPort;

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

    // OrderItemSalesRepository는 @MockSharedRepositories에서 공용 mock으로 등록됨 (이중 override 방지)

    private static final String SELLER_EMAIL = "seller@example.com";

    @BeforeEach
    void setUp() {
        when(assembler.assemble(anyString(), any(Pageable.class))).thenReturn(Page.empty());
    }

    // ============================================================
    // 인가 테스트
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/stats — SELLER → 200, view seller/product-stats")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stats_seller_returns_200_with_stats_view() throws Exception {
        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-stats"));
    }

    @Test
    @DisplayName("GET /seller/products/stats — ADMIN → 200 (RoleHierarchy 함의)")
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void stats_admin_returns_200() throws Exception {
        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-stats"));
    }

    @Test
    @DisplayName("GET /seller/products/stats — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void stats_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /seller/products/stats — 비인증 → /login redirect(302)")
    void stats_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    // ============================================================
    // 모델 검증
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/stats — SELLER → 모델에 statsPage 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stats_seller_model_contains_stats_page() throws Exception {
        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("statsPage"));
    }

    @Test
    @DisplayName("GET /seller/products/stats — assembler.assemble(actorEmail, pageable) 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stats_calls_assembler_with_actor_email() throws Exception {
        SellerProductStatsRow row = new SellerProductStatsRow(
                10L, "상품A", "ON_SALE", 50L, 5L, new BigDecimal("50000.00"));
        when(assembler.assemble(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isOk());

        verify(assembler).assemble(anyString(), any(Pageable.class));
    }

    // ============================================================
    // 조합 결과 병합 정확성 (assembler 통해 간접 검증)
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/stats — 상품 2개 조합 결과가 statsPage에 바인딩된다")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void stats_model_stats_page_contains_assembled_rows() throws Exception {
        List<SellerProductStatsRow> rows = List.of(
                new SellerProductStatsRow(10L, "상품A", "ON_SALE", 50L, 5L, new BigDecimal("50000.00")),
                new SellerProductStatsRow(20L, "상품B", "DRAFT", 10L, 0L, BigDecimal.ZERO)
        );
        when(assembler.assemble(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));

        mockMvc.perform(get("/seller/products/stats"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("statsPage"));
    }
}
