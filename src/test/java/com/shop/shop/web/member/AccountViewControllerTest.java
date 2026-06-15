package com.shop.shop.web.member;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.AccountInfo;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.AccountFacade;
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
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * AccountViewController MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /account — 인증 → 200 + view "member/account" + 모델 accountInfo/passwordForm/profileForm</li>
 *   <li>GET /account — 비인증 → /login redirect</li>
 *   <li>POST /account/password — 성공 → redirect:/account?password + flashSuccess</li>
 *   <li>POST /account/password — @Valid 검증 실패 → 재렌더 + 비번 echo 금지</li>
 *   <li>POST /account/password — BusinessException(현재 비번 불일치) → 재렌더 + currentPassword 에러 + 비번 clear</li>
 *   <li>POST /account/profile — 성공 → redirect:/account?profile + flashSuccess</li>
 *   <li>POST /account/profile — @Valid 검증 실패 → 재렌더</li>
 *   <li>POST /account/withdraw — 성공 → 세션 무효화 + redirect:/login?withdraw (flash 미단언)</li>
 *   <li>CSRF 없음 → 403</li>
 * </ul>
 *
 * <p>AccountFacade(@MockitoBean)로 도메인 로직을 격리한다.
 * FakeRefreshTokenStore: Redis 미기동.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AccountViewControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String CURRENT_PW = "currentPw1!";
    private static final String NEW_PW = "newPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountFacade accountFacade;

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

    @BeforeEach
    void setUp() {
        // stub: getAccountInfo → 표시용 AccountInfo (비번·해시 미포함)
        when(accountFacade.getAccountInfo(anyString()))
                .thenReturn(new AccountInfo(EMAIL, "홍길동", "010-0000-0000"));
    }

    // ============================================================
    // GET /account
    // ============================================================

    @Test
    @DisplayName("GET /account — 인증 사용자 → 200 + view 'member/account' + 모델 accountInfo/passwordForm/profileForm")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void get_account_authenticated_returns_200_with_model() throws Exception {
        mockMvc.perform(get("/account"))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                .andExpect(model().attributeExists("accountInfo"))
                .andExpect(model().attributeExists("passwordForm"))
                .andExpect(model().attributeExists("profileForm"));
    }

    @Test
    @DisplayName("GET /account — accountInfo 에 비밀번호·해시 미포함 (email/name/phone만 존재)")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void get_account_does_not_expose_password_hash() throws Exception {
        // AccountInfo 는 record(final component accessor) 이므로 모델 속성을 직접 꺼내 단언한다.
        // hasProperty 는 JavaBean 프로퍼티(getXxx)가 없으면 "No property" 오류 발생 — record는 조심.
        mockMvc.perform(get("/account"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    AccountInfo info = (AccountInfo) result.getModelAndView()
                            .getModel().get("accountInfo");
                    org.assertj.core.api.Assertions.assertThat(info).isNotNull();
                    org.assertj.core.api.Assertions.assertThat(info.email()).isEqualTo(EMAIL);
                    org.assertj.core.api.Assertions.assertThat(info.name()).isEqualTo("홍길동");
                    org.assertj.core.api.Assertions.assertThat(info.phone()).isEqualTo("010-0000-0000");
                });
    }

    @Test
    @DisplayName("GET /account — 비인증 → /login redirect (302)")
    void get_account_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/account"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // POST /account/password — 비밀번호 변경
    // ============================================================

    @Test
    @DisplayName("POST /account/password — 성공 → redirect:/account?password + flashSuccess")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_success_redirects_with_flash() throws Exception {
        mockMvc.perform(post("/account/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", CURRENT_PW)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account?password"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(accountFacade).changePassword(EMAIL, CURRENT_PW, NEW_PW);
    }

    @Test
    @DisplayName("POST /account/password — @Valid 실패(newPassword 8자 미만) → 재렌더 + 비번 echo 금지")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_validation_failure_rerenders_with_cleared_fields() throws Exception {
        mockMvc.perform(post("/account/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", CURRENT_PW)
                        .param("newPassword", "short")   // 8자 미만
                        .param("newPasswordConfirm", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                // 비번 echo 금지: clear 후 null/빈값이어야 함
                .andExpect(model().attribute("passwordForm",
                        hasProperty("currentPassword", nullValue())))
                .andExpect(model().attribute("passwordForm",
                        hasProperty("newPassword", nullValue())))
                .andExpect(model().attribute("passwordForm",
                        hasProperty("newPasswordConfirm", nullValue())));
    }

    @Test
    @DisplayName("POST /account/password — @Valid 실패(newPasswordConfirm 불일치) → 재렌더 + newPasswordConfirm 에러")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_confirm_mismatch_rerenders_with_field_error() throws Exception {
        mockMvc.perform(post("/account/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", CURRENT_PW)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", "differentPw1!"))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                .andExpect(model().attributeHasFieldErrors("passwordForm", "newPasswordConfirm"))
                // 비번 echo 금지
                .andExpect(model().attribute("passwordForm",
                        hasProperty("currentPassword", nullValue())));
    }

    @Test
    @DisplayName("POST /account/password — BusinessException(현재 비번 불일치) → 재렌더 + currentPassword 에러 + 비번 clear")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_current_mismatch_rerenders_with_current_password_error() throws Exception {
        doThrow(new BusinessException("현재 비밀번호가 일치하지 않습니다."))
                .when(accountFacade).changePassword(anyString(), anyString(), anyString());

        mockMvc.perform(post("/account/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", "wrongPw1!")
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                .andExpect(model().attributeHasFieldErrors("passwordForm", "currentPassword"))
                // 비번 clear 확인
                .andExpect(model().attribute("passwordForm",
                        hasProperty("currentPassword", nullValue())))
                .andExpect(model().attribute("passwordForm",
                        hasProperty("newPassword", nullValue())))
                .andExpect(model().attribute("passwordForm",
                        hasProperty("newPasswordConfirm", nullValue())));
    }

    @Test
    @DisplayName("POST /account/password — 재렌더 시 accountInfo/profileForm 도 모델에 포함")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_validation_failure_repopulates_model() throws Exception {
        mockMvc.perform(post("/account/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", CURRENT_PW)
                        .param("newPassword", "short")
                        .param("newPasswordConfirm", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                .andExpect(model().attributeExists("accountInfo"))
                .andExpect(model().attributeExists("profileForm"));
    }

    @Test
    @DisplayName("POST /account/password — CSRF 없음 → 403")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_password_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/account/password")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("currentPassword", CURRENT_PW)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // POST /account/profile — 정보 수정
    // ============================================================

    @Test
    @DisplayName("POST /account/profile — 성공 → redirect:/account?profile + flashSuccess")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_profile_success_redirects_with_flash() throws Exception {
        mockMvc.perform(post("/account/profile")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "김철수")
                        .param("phone", "010-1234-5678"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account?profile"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(accountFacade).updateProfile(EMAIL, "김철수", "010-1234-5678");
    }

    @Test
    @DisplayName("POST /account/profile — @Valid 실패(name 공백) → 재렌더 + name 에러")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_profile_blank_name_rerenders_with_error() throws Exception {
        mockMvc.perform(post("/account/profile")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "")   // 공백 — @NotBlank 위반
                        .param("phone", "010-0000-0000"))
                .andExpect(status().isOk())
                .andExpect(view().name("member/account"))
                .andExpect(model().attributeHasFieldErrors("profileForm", "name"))
                // 재렌더 시 accountInfo/passwordForm 도 포함
                .andExpect(model().attributeExists("accountInfo"))
                .andExpect(model().attributeExists("passwordForm"));
    }

    @Test
    @DisplayName("POST /account/profile — CSRF 없음 → 403")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_profile_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/account/profile")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "김철수")
                        .param("phone", "010-0000-0000"))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // POST /account/withdraw — 탈퇴
    // ============================================================

    @Test
    @DisplayName("POST /account/withdraw — 성공 → redirect:/login?withdraw (세션 무효화, flash 미사용)")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_withdraw_success_redirects_to_login_with_withdraw_param() throws Exception {
        mockMvc.perform(post("/account/withdraw")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?withdraw*"));

        verify(accountFacade).withdraw(EMAIL);
    }

    @Test
    @DisplayName("POST /account/withdraw — 비인증 → /login redirect")
    void post_withdraw_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(post("/account/withdraw")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("POST /account/withdraw — CSRF 없음 → 403")
    @WithMockUser(username = EMAIL, roles = "CONSUMER")
    void post_withdraw_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/account/withdraw"))
                .andExpect(status().isForbidden());
    }
}
