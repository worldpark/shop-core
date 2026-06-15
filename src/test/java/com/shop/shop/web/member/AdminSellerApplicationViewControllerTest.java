package com.shop.shop.web.member;

import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.member.spi.AdminSellerApplicationFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
 * AdminSellerApplicationViewController MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /admin/seller-applications: ADMIN 200 + view + model applications/status</li>
 *   <li>GET /admin/seller-applications: status 필터 인자 전달 확인</li>
 *   <li>POST .../approve: 성공 → redirect+flashSuccess</li>
 *   <li>POST .../reject: 성공 → redirect+flashSuccess</li>
 *   <li>CONSUMER/SELLER → 403, 비인증 → /login redirect</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminSellerApplicationViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminSellerApplicationFacade adminSellerApplicationFacade;

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
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    // ============================================================
    // GET /admin/seller-applications
    // ============================================================

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    @DisplayName("GET /admin/seller-applications — ADMIN → 200 + view 'admin/seller-applications' + model")
    void list_admin_returns_200_with_model() throws Exception {
        when(adminSellerApplicationFacade.search(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/seller-applications"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/seller-applications"))
                .andExpect(model().attributeExists("applications"))
                .andExpect(model().attributeExists("status"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    @DisplayName("GET /admin/seller-applications?status=PENDING — status 파라미터 모델에 포함")
    void list_with_status_filter_includes_status_in_model() throws Exception {
        when(adminSellerApplicationFacade.search(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/seller-applications").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("status", "PENDING"));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    @DisplayName("GET /admin/seller-applications — CONSUMER → 403")
    void list_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/admin/seller-applications"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("GET /admin/seller-applications — SELLER → 403")
    void list_seller_returns_403() throws Exception {
        mockMvc.perform(get("/admin/seller-applications"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/seller-applications — 비인증 → /login redirect")
    void list_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/seller-applications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // POST /admin/seller-applications/{id}/approve
    // ============================================================

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    @DisplayName("POST .../approve — ADMIN → redirect:/admin/seller-applications + flashSuccess")
    void approve_admin_redirects_with_flash_success() throws Exception {
        mockMvc.perform(post("/admin/seller-applications/{id}/approve", 42L)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/seller-applications"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    @DisplayName("POST .../approve — CONSUMER → 403")
    void approve_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/admin/seller-applications/{id}/approve", 42L)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST .../approve — 비인증 → /login redirect")
    void approve_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(post("/admin/seller-applications/{id}/approve", 42L)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ============================================================
    // POST /admin/seller-applications/{id}/reject
    // ============================================================

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    @DisplayName("POST .../reject — ADMIN + rejectReason → redirect + flashSuccess")
    void reject_admin_with_reason_redirects_with_flash_success() throws Exception {
        mockMvc.perform(post("/admin/seller-applications/{id}/reject", 50L)
                        .with(csrf())
                        .param("rejectReason", "서류 미비"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/seller-applications"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    @DisplayName("POST .../reject — CONSUMER → 403")
    void reject_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/admin/seller-applications/{id}/reject", 50L)
                        .with(csrf())
                        .param("rejectReason", "사유"))
                .andExpect(status().isForbidden());
    }
}
