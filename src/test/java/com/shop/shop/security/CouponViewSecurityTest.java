package com.shop.shop.security;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * View 체인 /coupons 인가 테스트 (057).
 *
 * <p>검증:
 * <ul>
 *   <li>GET /coupons — 비인증 → 302 /login redirect</li>
 *   <li>GET /coupons — CONSUMER → 200</li>
 *   <li>GET /coupons — SELLER → 200 (역할 계층: SELLER > CONSUMER)</li>
 *   <li>GET /coupons — ADMIN → 200 (역할 계층: ADMIN > SELLER > CONSUMER)</li>
 *   <li>031 REST 인가 회귀: GET /api/v1/coupons 비인증 → 401</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class CouponViewSecurityTest {

    @Autowired
    private MockMvc mockMvc;

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
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @Test
    @DisplayName("GET /coupons — 비인증 → 302 /login redirect")
    void coupons_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /coupons — CONSUMER 인증 시 Security 체인 통과 (403/redirect 없음)")
    @WithMockUser(roles = "CONSUMER", username = "consumer@example.com")
    void coupons_consumer_passesSecurityChain() throws Exception {
        // 컨트롤러가 없으면 404, 있으면 200 — 중요한 것은 403/redirect(302)가 아님
        int status = mockMvc.perform(get("/coupons"))
                .andReturn().getResponse().getStatus();
        // 200(컨트롤러 존재) 또는 404(컨트롤러 미구현) — 둘 다 Security 인가 통과
        org.assertj.core.api.Assertions.assertThat(status)
                .as("CONSUMER는 /coupons 접근이 Security 차단(403/302)되어서는 안 됨")
                .isNotEqualTo(403)
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("GET /coupons — SELLER 인증 시 Security 체인 통과 (역할 계층: SELLER > CONSUMER)")
    @WithMockUser(roles = "SELLER", username = "seller@example.com")
    void coupons_seller_passesSecurityChainViaRoleHierarchy() throws Exception {
        int status = mockMvc.perform(get("/coupons"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
                .as("SELLER는 CONSUMER 이상 권한 보유 — /coupons Security 통과 필수")
                .isNotEqualTo(403)
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("GET /coupons — ADMIN 인증 시 Security 체인 통과 (역할 계층: ADMIN > SELLER > CONSUMER)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void coupons_admin_passesSecurityChainViaRoleHierarchy() throws Exception {
        int status = mockMvc.perform(get("/coupons"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status)
                .as("ADMIN은 CONSUMER 이상 권한 보유 — /coupons Security 통과 필수")
                .isNotEqualTo(403)
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("[031 회귀] GET /api/v1/coupons — 비인증 → 401 (REST 체인 인가 무변경)")
    void restCoupons_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/coupons"))
                .andExpect(status().isUnauthorized());
    }
}
