package com.shop.shop.web.coupon;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.CouponAlreadyOwnedException;
import com.shop.shop.common.exception.CouponNotFoundException;
import com.shop.shop.common.exception.CouponNotClaimableException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.spi.CouponFacade;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
 * CouponViewController MockMvc 통합 테스트.
 *
 * <p>CouponFacade(@MockitoBean)로 도메인 로직을 격리한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /coupons — 비인증 → /login redirect</li>
 *   <li>GET /coupons — CONSUMER → 200, view name "coupon/wallet", 모델 couponWallet/couponClaimForm</li>
 *   <li>GET /coupons — email이 facade.getMyWallet에 전달됨</li>
 *   <li>POST /coupons/claim — 성공 → flashSuccess + redirect:/coupons</li>
 *   <li>POST /coupons/claim — code 누락(검증 실패) → flashError + redirect:/coupons</li>
 *   <li>POST /coupons/claim — CouponNotFoundException(404) → flashError + redirect:/coupons</li>
 *   <li>POST /coupons/claim — CouponAlreadyOwnedException(409) → flashError + redirect:/coupons</li>
 *   <li>POST /coupons/claim — CouponNotClaimableException(400) → flashError + redirect:/coupons</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class CouponViewControllerTest {

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
    private CouponFacade couponFacade;

    private static final String USER_EMAIL = "consumer@example.com";

    @BeforeEach
    void setUp() {
        when(couponFacade.getMyWallet(anyString())).thenReturn(sampleWallet());
    }

    // ============================================================
    // GET /coupons
    // ============================================================

    @Test
    @DisplayName("GET /coupons — 비인증 → /login redirect (302)")
    void wallet_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /coupons — CONSUMER → 200")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void wallet_consumer_returns200() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /coupons — view name coupon/wallet")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void wallet_returnsWalletView() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk())
                .andExpect(view().name("coupon/wallet"));
    }

    @Test
    @DisplayName("GET /coupons — 모델 couponWallet 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void wallet_modelContainsCouponWallet() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("couponWallet"));
    }

    @Test
    @DisplayName("GET /coupons — 모델 couponClaimForm 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void wallet_modelContainsCouponClaimForm() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("couponClaimForm"));
    }

    @Test
    @DisplayName("GET /coupons — email이 facade.getMyWallet에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void wallet_delegatesToFacadeWithEmail() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isOk());

        verify(couponFacade).getMyWallet(USER_EMAIL);
    }

    // ============================================================
    // POST /coupons/claim
    // ============================================================

    @Test
    @DisplayName("POST /coupons/claim — 성공 → flashSuccess + redirect:/coupons")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_success_flashSuccessAndRedirect() throws Exception {
        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", "SUMMER2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @DisplayName("POST /coupons/claim — email이 facade.claim에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_emailPassedToFacade() throws Exception {
        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", "SUMMER2026"))
                .andExpect(status().is3xxRedirection());

        verify(couponFacade).claim(eq(USER_EMAIL), eq("SUMMER2026"));
    }

    @Test
    @DisplayName("POST /coupons/claim — code 누락(검증 실패) → flashError + redirect:/coupons")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_missingCode_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /coupons/claim — CouponNotFoundException(404) → flashError + redirect:/coupons")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_couponNotFound_flashErrorAndRedirect() throws Exception {
        doThrow(new CouponNotFoundException()).when(couponFacade).claim(anyString(), anyString());

        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", "INVALID"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /coupons/claim — CouponAlreadyOwnedException(409) → flashError + redirect:/coupons")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_couponAlreadyOwned_flashErrorAndRedirect() throws Exception {
        doThrow(new CouponAlreadyOwnedException()).when(couponFacade).claim(anyString(), anyString());

        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", "DUPLICATE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /coupons/claim — CouponNotClaimableException(400) → flashError + redirect:/coupons")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void claim_couponNotClaimable_flashErrorAndRedirect() throws Exception {
        doThrow(new CouponNotClaimableException()).when(couponFacade).claim(anyString(), anyString());

        mockMvc.perform(post("/coupons/claim")
                        .with(csrf())
                        .param("code", "EXPIRED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private List<UserCouponResponse> sampleWallet() {
        Instant now = Instant.now();
        return List.of(
                new UserCouponResponse(
                        1L, 10L, "SUMMER2026", "여름 할인 쿠폰",
                        "fixed", new BigDecimal("1000"),
                        new BigDecimal("10000"), null,
                        now.minusSeconds(86400), now.plusSeconds(86400 * 30),
                        false, null, false
                )
        );
    }
}
