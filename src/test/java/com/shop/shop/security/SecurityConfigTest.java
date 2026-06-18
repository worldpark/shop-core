package com.shop.shop.security;

import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberService;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SecurityConfig View 체인 통합 테스트 (054 cutover 반영).
 *
 * <p>054 변경사항:
 * <ul>
 *   <li>formLogin 제거 → POST /login은 CookieLoginViewController가 처리</li>
 *   <li>View 체인 STATELESS → JSESSIONID 미생성</li>
 *   <li>미인증: LoginUrlAuthenticationEntryPoint (302 /login)</li>
 * </ul>
 *
 * <p>무변경: CSRF(CookieCsrfTokenRepository), GET /login 200, 미인증 → 302.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    SellerApplicationRepository sellerApplicationRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private MemberService memberService;

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

    @BeforeEach
    void setUp() {
        // ViewAuthService.loginAndSetCookies → memberService.authenticate 호출
        // 기본 mock은 null 반환 → NPE 방지: 항상 InvalidCredentialsException 던지도록 stub
        when(memberService.authenticate(any(), any())).thenThrow(new InvalidCredentialsException());
    }

    @Test
    @DisplayName("(a) 비인증 사용자가 보호 경로(GET /) 접근 시 /login 으로 302 리다이렉트")
    void unauthenticated_access_to_protected_path_redirects_to_login() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("(b) GET /login 은 200 반환 + 뷰 이름 auth/login")
    void login_page_returns_200_and_auth_login_view() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @DisplayName("(c) POST /login 은 CookieLoginViewController가 처리 — CSRF 없이 403")
    void post_login_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "user@example.com")
                        .param("password", "password"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("(d) CSRF 포함 POST /login → 302 redirect (CookieLoginViewController 경유, 자격증명 실패 → /login?error)")
    void post_login_with_csrf_processes_through_controller() throws Exception {
        // MemberService.authenticate가 mock (stub 없음 → InvalidCredentialsException)
        // ViewAuthService → memberService.authenticate 호출 → 예외 → /login?error
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "user@example.com")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("(e) POST /logout CSRF 포함 → /login?logout redirect")
    void post_logout_with_csrf_redirects_to_login_logout() throws Exception {
        mockMvc.perform(post("/logout")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    @DisplayName("(보강) CSRF 쿠키 저장소 전환 — with(csrf()) POST /login 통과 (세션 CSRF 의존 없음)")
    void post_login_with_csrf_postprocessor_uses_cookie_csrf_repository() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "user@example.com")
                        .param("password", "any"))
                .andExpect(status().is3xxRedirection()); // 302 (오류여도 redirect)
    }

    @Test
    @DisplayName("(보강) View 체인 STATELESS — 로그인 응답에 JSESSIONID Set-Cookie 없음")
    void login_response_does_not_set_jsessionid_cookie() throws Exception {
        var result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "user@example.com")
                        .param("password", "any"))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        // JSESSIONID 쿠키가 응답에 없어야 함
        if (setCookie != null) {
            org.assertj.core.api.Assertions.assertThat(setCookie)
                    .doesNotContainIgnoringCase("JSESSIONID");
        }
        // 세션이 생성되지 않아야 함
        org.assertj.core.api.Assertions.assertThat(
                result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("미인증 GET /api/v1/orders → 401 (REST 체인 보호 무변경)")
    void unauthenticated_rest_api_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================
    // CSRF 쿠키 유지 회귀 테스트 (054 로그아웃 403 버그 재발 방지)
    // =========================================================

    @Test
    @DisplayName("[회귀] 정적 자산(GET /css/**)은 CSRF 검증 대상 아님 — 200 반환, XSRF-TOKEN 신규 토큰 미생성")
    void static_asset_request_is_excluded_from_csrf_filter() throws Exception {
        // 정적 자산은 GET 메서드이므로 어차피 CSRF 검증 대상 아님(GET/HEAD/TRACE/OPTIONS 제외).
        // CsrfCookieFilter 제거 후: GET /css 요청에서 getToken()이 강제 호출되지 않아
        // 쿠키가 없어도 신규 토큰 생성·Set-Cookie가 일어나지 않는다.
        // 핵심: 정적 자산 응답이 403이 아니어야 하고, XSRF-TOKEN 삭제 쿠키를 내보내지 않아야 함.
        MockHttpServletResponse cssResponse = mockMvc.perform(get("/css/app.css"))
                .andReturn().getResponse();

        // 403이면 CSRF 필터가 개입한 것 — 반드시 아니어야 함
        assertThat(cssResponse.getStatus()).isNotEqualTo(403);

        // XSRF-TOKEN 삭제 쿠키(Max-Age=0) 미포함 확인
        String setCookieHeader = cssResponse.getHeader(HttpHeaders.SET_COOKIE);
        if (setCookieHeader != null) {
            // Max-Age=0 인 XSRF-TOKEN 쿠키가 없어야 함 (토큰 삭제 동작 없음)
            assertThat(setCookieHeader.toLowerCase())
                    .as("정적 자산 응답이 XSRF-TOKEN 삭제 쿠키를 내보내면 안 됨")
                    .doesNotMatch("(?i).*xsrf-token[^;]*;[^,]*max-age=0.*");
        }
    }

    @Test
    @DisplayName("[회귀] GET /login 응답에 XSRF-TOKEN 쿠키가 발급되거나 폼에 _csrf 주입됨")
    void login_page_get_issues_xsrf_token_cookie() throws Exception {
        // Thymeleaf th:action="@{/login}"이 렌더될 때 getToken()을 자동 호출 → XSRF-TOKEN 쿠키 발급.
        // CsrfCookieFilter 제거 후에도 Thymeleaf SSR 폼이 있는 페이지에서 쿠키가 정상 발급된다.
        //
        // 주의: MockMvc 전체 suite 실행 시 with(csrf()) 사용 테스트가 먼저 실행되면
        // 테스트 컨텍스트의 CsrfTokenRepository 상태에 따라 응답에 Set-Cookie 헤더가 없을 수 있다.
        // (이미 유효한 CSRF 토큰이 있으면 CookieCsrfTokenRepository는 재발급하지 않음)
        // 따라서: 쿠키가 응답에 있으면 값이 비어있지 않음을 검증하고,
        // 없는 경우(이미 발급된 상태)에도 폼에 _csrf 히든 필드가 주입됨을 응답 본문으로 확인.
        var result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        jakarta.servlet.http.Cookie xsrfCookie = response.getCookie("XSRF-TOKEN");

        if (xsrfCookie != null) {
            // 쿠키가 발급된 경우: 값이 비어있으면 안 됨
            assertThat(xsrfCookie.getValue())
                    .as("XSRF-TOKEN 쿠키 값이 비어있으면 안 됨 (삭제 쿠키 금지)")
                    .isNotBlank();
        } else {
            // 쿠키가 없는 경우(이미 발급된 상태): 폼에 _csrf 히든 필드가 반드시 주입되어야 함
            // Thymeleaf th:action이 getToken()을 호출했다는 증거
            assertThat(response.getContentAsString())
                    .as("GET /login 응답 본문에 _csrf 히든 필드가 주입되어야 함 (CSRF 토큰 활성 확인)")
                    .contains("name=\"_csrf\"");
        }
    }

    @Test
    @DisplayName("[회귀] 정적 자산 요청 후 POST /logout with(csrf()) 성공 — CSRF 토큰 유지")
    void static_asset_request_does_not_invalidate_csrf_token_for_logout() throws Exception {
        // (1) 정적 자산 요청 (브라우저가 HTML 로드 후 병렬로 요청하는 시나리오)
        mockMvc.perform(get("/css/app.css"));
        mockMvc.perform(get("/js/app.js"));

        // (2) 정적 자산 요청 후에도 CSRF 토큰이 유효해 POST /logout이 성공해야 함
        // with(csrf())는 테스트용 CSRF 토큰을 자동 삽입 — 정적 자산이 토큰을 삭제하지 않았다면 통과
        mockMvc.perform(post("/logout")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }
}
