package com.shop.shop.web.admin;

import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.order.spi.AdminOrderStatsFacade;
import com.shop.shop.product.spi.AdminProductStatsFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import com.shop.shop.web.admin.dto.AdminDashboardView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * {@link AdminDashboardViewController} + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>인가: ADMIN 200 / CONSUMER 403 / SELLER 403 / 비인증 로그인 리다이렉트</li>
 *   <li>모델 키 {@code dashboard} 존재</li>
 *   <li>뷰 이름 {@code admin/dashboard}</li>
 *   <li>assembler.build() 호출 검증</li>
 * </ul>
 *
 * <p>{@link AdminDashboardAssembler}는 @MockitoBean으로 격리한다.
 * 조합 로직 단위 테스트는 {@link AdminDashboardAssemblerTest}에서 수행.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminDashboardViewControllerTest {

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

    // OrderItemSalesRepository / StockLedgerRepository 는 @MockSharedRepositories에서 공용 mock 등록됨

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
    @DisplayName("GET /admin/dashboard — ADMIN → 200, view admin/dashboard")
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    void dashboard_admin_returns_200_with_dashboard_view() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"));
    }

    @Test
    @DisplayName("GET /admin/dashboard — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void dashboard_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/dashboard — SELLER → 403")
    @WithMockUser(username = "seller@example.com", roles = "SELLER")
    void dashboard_seller_returns_403() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/dashboard — 비인증 → /login redirect (302)")
    void dashboard_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    // ============================================================
    // 모델 검증
    // ============================================================

    @Test
    @DisplayName("GET /admin/dashboard — ADMIN → 모델에 'dashboard' 키 존재")
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    void dashboard_admin_model_contains_dashboard_key() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("dashboard"));
    }

    @Test
    @DisplayName("GET /admin/dashboard — assembler.build() 호출 검증")
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    void dashboard_calls_assembler_build() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk());

        verify(assembler).build();
    }
}
