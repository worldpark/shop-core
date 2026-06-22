package com.shop.shop.web.coupon;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.DuplicateCouponCodeException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.spi.AdminCouponFacade;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * AdminCouponViewController MockMvc 통합 테스트.
 *
 * <p>AdminCouponFacade(@MockitoBean)로 도메인 로직을 격리한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /admin/coupons — ADMIN → 200, view "admin/coupons", 모델 coupons/couponForm</li>
 *   <li>GET /admin/coupons — CONSUMER → 403 (비ADMIN 차단)</li>
 *   <li>GET /admin/coupons — 비인증 → /login redirect</li>
 *   <li>POST /admin/coupons — 성공 → flashSuccess + redirect:/admin/coupons</li>
 *   <li>POST /admin/coupons — 폼 검증 실패 → 동일 뷰 재렌더 + coupons 모델 존재</li>
 *   <li>POST /admin/coupons — DuplicateCouponCodeException → flashError + redirect</li>
 * </ul>
 *
 * <p>인가: SecurityConfig View 체인 /admin/** hasRole ADMIN 검증 (컨트롤러 권한 분기 없음 — 041 패턴).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminCouponViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    private OrderItemQueryRepository orderItemQueryRepository;
    @MockitoBean
    private ReviewRepository reviewRepository;

    @MockitoBean
    private AdminCouponFacade adminCouponFacade;

    @BeforeEach
    void setUp() {
        when(adminCouponFacade.list()).thenReturn(sampleCouponList());
        when(adminCouponFacade.create(any())).thenReturn(sampleCouponResponse());
    }

    // ============================================================
    // GET /admin/coupons
    // ============================================================

    @Test
    @DisplayName("GET /admin/coupons — 비인증 → /login redirect (302)")
    void list_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /admin/coupons — CONSUMER → 403 (비ADMIN 차단)")
    @WithMockUser(roles = "CONSUMER")
    void list_consumer_forbidden() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/coupons — ADMIN → 200")
    @WithMockUser(roles = "ADMIN")
    void list_admin_returns200() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/coupons — view name admin/coupons")
    @WithMockUser(roles = "ADMIN")
    void list_returnsAdminCouponsView() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/coupons"));
    }

    @Test
    @DisplayName("GET /admin/coupons — 모델 coupons 존재")
    @WithMockUser(roles = "ADMIN")
    void list_modelContainsCoupons() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("coupons"));
    }

    @Test
    @DisplayName("GET /admin/coupons — 모델 couponForm 존재")
    @WithMockUser(roles = "ADMIN")
    void list_modelContainsCouponForm() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("couponForm"));
    }

    // ============================================================
    // POST /admin/coupons
    // ============================================================

    @Test
    @DisplayName("POST /admin/coupons — 성공 → flashSuccess + redirect:/admin/coupons")
    @WithMockUser(roles = "ADMIN")
    void create_success_flashSuccessAndRedirect() throws Exception {
        mockMvc.perform(post("/admin/coupons")
                        .with(csrf())
                        .param("code", "TEST2026")
                        .param("name", "테스트 쿠폰")
                        .param("discountType", "fixed")
                        .param("value", "1000")
                        .param("startsAt", "2026-01-01T00:00")
                        .param("endsAt", "2026-12-31T23:59")
                        .param("isActive", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/coupons"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST /admin/coupons — code 누락(폼 검증 실패) → 동일 뷰 재렌더 + coupons 모델 존재")
    @WithMockUser(roles = "ADMIN")
    void create_missingCode_rerenderWithCoupons() throws Exception {
        mockMvc.perform(post("/admin/coupons")
                        .with(csrf())
                        .param("code", "")
                        .param("name", "테스트 쿠폰")
                        .param("discountType", "fixed")
                        .param("value", "1000")
                        .param("startsAt", "2026-01-01T00:00")
                        .param("endsAt", "2026-12-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/coupons"))
                .andExpect(model().attributeExists("coupons"));
    }

    @Test
    @DisplayName("POST /admin/coupons — value 누락(폼 검증 실패) → 동일 뷰 재렌더")
    @WithMockUser(roles = "ADMIN")
    void create_missingValue_rerenderView() throws Exception {
        mockMvc.perform(post("/admin/coupons")
                        .with(csrf())
                        .param("code", "TEST2026")
                        .param("name", "테스트 쿠폰")
                        .param("discountType", "fixed")
                        .param("startsAt", "2026-01-01T00:00")
                        .param("endsAt", "2026-12-31T23:59"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/coupons"));
    }

    @Test
    @DisplayName("POST /admin/coupons — DuplicateCouponCodeException → flashError + redirect:/admin/coupons")
    @WithMockUser(roles = "ADMIN")
    void create_duplicateCode_flashErrorAndRedirect() throws Exception {
        when(adminCouponFacade.create(any())).thenThrow(new DuplicateCouponCodeException());

        mockMvc.perform(post("/admin/coupons")
                        .with(csrf())
                        .param("code", "DUPLICATE")
                        .param("name", "중복 쿠폰")
                        .param("discountType", "fixed")
                        .param("value", "1000")
                        .param("startsAt", "2026-01-01T00:00")
                        .param("endsAt", "2026-12-31T23:59"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/coupons"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private List<AdminCouponResponse> sampleCouponList() {
        return List.of(sampleCouponResponse());
    }

    private AdminCouponResponse sampleCouponResponse() {
        return new AdminCouponResponse(
                1L, "SUMMER2026", "여름 할인 쿠폰",
                "fixed", new BigDecimal("1000"),
                new BigDecimal("10000"), null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
                100, 5, true
        );
    }
}
