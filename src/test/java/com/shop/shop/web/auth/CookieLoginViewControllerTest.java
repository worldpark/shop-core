package com.shop.shop.web.auth;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.ViewAuthFacade;
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
import com.shop.shop.security.AuthCookies;
import com.shop.shop.security.AuthTokenIssuer;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CookieLoginViewController MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>POST /login 성공 → viewAuthFacade.login 호출 + redirect:/</li>
 *   <li>POST /login 실패(InvalidCredentialsException) → redirect:/login?error</li>
 *   <li>POST /logout → viewAuthFacade.revoke 호출 + authCookies.clearTokens 호출 + redirect:/login?logout</li>
 * </ul>
 *
 * <p>쿠키 속성(HttpOnly·Secure·SameSite) 단언은 AuthCookiesTest가 커버하므로 여기서는
 * 컨트롤러 계약(호출 위임·redirect)만 검증한다.
 *
 * <p>ViewAuthFacade(@MockitoBean)으로 member 도메인 로직을 격리한다.
 * AuthCookies(@MockitoBean)으로 쿠키 I/O를 격리하고 호출 여부를 단언한다.
 * FakeRefreshTokenStore: Redis 미기동.
 *
 * <p>LogoutFilter 비활성화 (SecurityConfig.logout.disable()) 적용 후(054):
 * POST /logout이 기본 LogoutFilter에 가로채이지 않고 CookieLoginViewController.logout()에 직접 도달한다.
 * 따라서 revoke/clearTokens 호출을 여기서 단언할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class CookieLoginViewControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "password";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ViewAuthFacade viewAuthFacade;

    @MockitoBean
    private AuthCookies authCookies;

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

    // ───────────────────────────── POST /login ─────────────────────────────

    @Test
    @DisplayName("POST /login 성공 → viewAuthFacade.login 호출 + redirect:/")
    void login_success_redirects_to_home() throws Exception {
        AuthTokenIssuer.IssuedTokens fakeTokens =
                new AuthTokenIssuer.IssuedTokens("access.token.value", "refresh.token.value", 1800L);
        when(viewAuthFacade.login(EMAIL, PASSWORD)).thenReturn(fakeTokens);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        verify(viewAuthFacade).login(EMAIL, PASSWORD);
    }

    @Test
    @DisplayName("POST /login 실패(InvalidCredentialsException) → redirect:/login?error")
    void login_failure_redirects_to_error() throws Exception {
        when(viewAuthFacade.login(EMAIL, "wrong")).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", EMAIL)
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    // ───────────────────────────── POST /logout ─────────────────────────────

    @Test
    @DisplayName("POST /logout → viewAuthFacade.revoke 호출 + authCookies.clearTokens 호출 + redirect:/login?logout")
    void logout_revokes_token_clears_cookies_and_redirects() throws Exception {
        // SecurityConfig.logout.disable() 적용 후(054): 기본 LogoutFilter가 비활성화되어
        // POST /logout이 CookieLoginViewController.logout()에 직접 도달한다.
        // access_token 쿠키 없이 요청 → readAccess returns null → revoke(null) 호출 (fail-safe 계약)
        mockMvc.perform(post("/logout")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        // 컨트롤러가 실제로 호출되었음을 단언 (LogoutFilter 가로채기 없음 확인)
        verify(viewAuthFacade).revoke(any());
        verify(authCookies).clearTokens(any());
    }
}
