package com.shop.shop.web.auth;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InvalidPasswordResetTokenException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.PasswordResetFacade;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakePasswordResetTokenStore;
import com.shop.shop.security.support.FakeRefreshTokenStore;
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

import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * PasswordResetViewController MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /password-reset → 200 + view "auth/password-reset-request" + 모델 passwordResetForm</li>
 *   <li>POST /password-reset(존재/미존재 무관) → redirect:/password-reset?sent (enumeration-safe)</li>
 *   <li>POST /password-reset 이메일 형식 오류 → 재렌더 + BindingResult 에러</li>
 *   <li>GET /password-reset/confirm?token=valid → resetTokenValid=true + 폼; peek 호출(consume 미호출)</li>
 *   <li>GET /password-reset/confirm?token=invalid → resetTokenValid=false 안내; peek 호출(consume 미호출)</li>
 *   <li>POST /password-reset/confirm 성공 → redirect:/login?reset</li>
 *   <li>POST /password-reset/confirm 무효 토큰 → 재렌더 안내 + 비번 clear</li>
 *   <li>POST /password-reset/confirm 검증 실패 → 비번 echo 없음 단언</li>
 *   <li>POST /password-reset/confirm 비번 불일치 → newPasswordConfirm 필드 BindingResult 에러 + 비번 clear</li>
 *   <li>login.html에 /password-reset 링크 렌더 단언</li>
 * </ul>
 *
 * <p>PasswordResetFacade(@MockitoBean)으로 도메인 로직을 격리한다.
 * FakeRefreshTokenStore + FakePasswordResetTokenStore: Redis 미기동.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FakeRefreshTokenStore.class, FakePasswordResetTokenStore.class})
class PasswordResetViewControllerTest {

    private static final String VALID_TOKEN = "validtoken1234567890abcdef";
    private static final String INVALID_TOKEN = "invalidtoken";
    private static final String NEW_PW = "newPassword1!";
    private static final String EMAIL = "user@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetFacade passwordResetFacade;

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

    // ============================================================
    // GET /password-reset
    // ============================================================

    @Test
    @DisplayName("GET /password-reset → 200 + view 'auth/password-reset-request' + 모델 passwordResetForm")
    void get_passwordReset_returns_200_with_form() throws Exception {
        mockMvc.perform(get("/password-reset"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-request"))
                .andExpect(model().attributeExists("passwordResetForm"));
    }

    // ============================================================
    // POST /password-reset
    // ============================================================

    @Test
    @DisplayName("POST /password-reset 존재 이메일 → redirect:/password-reset?sent (enumeration-safe)")
    void post_passwordReset_existingEmail_redirects_to_sent() throws Exception {
        mockMvc.perform(post("/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password-reset?sent"));

        verify(passwordResetFacade).requestReset(EMAIL);
    }

    @Test
    @DisplayName("POST /password-reset 미존재 이메일 → redirect:/password-reset?sent (enumeration-safe, 동일 결과)")
    void post_passwordReset_nonExistentEmail_redirects_to_sent_same_as_existing() throws Exception {
        // facade는 존재/미존재 무관 항상 정상 반환 (enumeration 방지)
        mockMvc.perform(post("/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "notfound@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password-reset?sent"));

        verify(passwordResetFacade).requestReset("notfound@example.com");
    }

    @Test
    @DisplayName("POST /password-reset 이메일 형식 오류 → 재렌더 + email 필드 BindingResult 에러")
    void post_passwordReset_invalidEmail_rerenders_with_error() throws Exception {
        mockMvc.perform(post("/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-request"))
                .andExpect(model().attributeHasFieldErrors("passwordResetForm", "email"));

        verify(passwordResetFacade, never()).requestReset(anyString());
    }

    @Test
    @DisplayName("POST /password-reset 이메일 공백 → 재렌더 + email 필드 에러, facade 미호출")
    void post_passwordReset_blankEmail_rerenders_with_error() throws Exception {
        mockMvc.perform(post("/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-request"))
                .andExpect(model().attributeHasFieldErrors("passwordResetForm", "email"));

        verify(passwordResetFacade, never()).requestReset(anyString());
    }

    // ============================================================
    // GET /password-reset/confirm
    // ============================================================

    @Test
    @DisplayName("GET /password-reset/confirm?token=valid → resetTokenValid=true + 폼; peek 호출(consume 미호출)")
    void get_confirmForm_validToken_returns_true_and_calls_peek_not_consume() throws Exception {
        when(passwordResetFacade.isTokenValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/password-reset/confirm").param("token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                .andExpect(model().attribute("resetTokenValid", true))
                .andExpect(model().attributeExists("passwordResetConfirmForm"));

        // 비소비 peek 호출 단언
        verify(passwordResetFacade).isTokenValid(VALID_TOKEN);
        // consume은 호출되지 않아야 함
        verify(passwordResetFacade, never()).confirmReset(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /password-reset/confirm?token=invalid → resetTokenValid=false 안내; peek 호출(consume 미호출)")
    void get_confirmForm_invalidToken_returns_false_and_calls_peek_not_consume() throws Exception {
        when(passwordResetFacade.isTokenValid(INVALID_TOKEN)).thenReturn(false);

        mockMvc.perform(get("/password-reset/confirm").param("token", INVALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                .andExpect(model().attribute("resetTokenValid", false));

        verify(passwordResetFacade).isTokenValid(INVALID_TOKEN);
        verify(passwordResetFacade, never()).confirmReset(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /password-reset/confirm (token 없음) → resetTokenValid=false, peek 미호출")
    void get_confirmForm_noToken_returns_false_without_peek() throws Exception {
        mockMvc.perform(get("/password-reset/confirm"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                .andExpect(model().attribute("resetTokenValid", false));

        verify(passwordResetFacade, never()).isTokenValid(anyString());
    }

    // ============================================================
    // POST /password-reset/confirm
    // ============================================================

    @Test
    @DisplayName("POST /password-reset/confirm 성공 → redirect:/login?reset")
    void post_confirm_success_redirects_to_login_reset() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", VALID_TOKEN)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?reset"));

        verify(passwordResetFacade).confirmReset(VALID_TOKEN, NEW_PW);
    }

    @Test
    @DisplayName("POST /password-reset/confirm 무효 토큰(InvalidPasswordResetTokenException) → 재렌더 + resetTokenValid=false + 비번 clear")
    void post_confirm_invalidToken_rerenders_with_invalid_flag_and_cleared_password() throws Exception {
        doThrow(new InvalidPasswordResetTokenException())
                .when(passwordResetFacade).confirmReset(anyString(), anyString());

        mockMvc.perform(post("/password-reset/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", INVALID_TOKEN)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                .andExpect(model().attribute("resetTokenValid", false))
                // 비번 clear 단언
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPassword", nullValue())))
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPasswordConfirm", nullValue())));
    }

    @Test
    @DisplayName("POST /password-reset/confirm 검증 실패(8자 미만) → 재렌더 + 비번 echo 없음 단언")
    void post_confirm_shortPassword_rerenders_with_cleared_password_no_echo() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", VALID_TOKEN)
                        .param("newPassword", "short")
                        .param("newPasswordConfirm", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                // 비번 echo 금지 단언
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPassword", nullValue())))
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPasswordConfirm", nullValue())));

        verify(passwordResetFacade, never()).confirmReset(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /password-reset/confirm 비번 불일치(실값 둘 다) → newPasswordConfirm 필드 에러 + 비번 clear (@PasswordMatches 일반화 실동작)")
    void post_confirm_passwordMismatch_rerenders_with_confirmField_error_and_cleared_passwords() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", VALID_TOKEN)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", "differentPassword1!"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                // @PasswordMatches(field="newPassword", confirmField="newPasswordConfirm") 실동작 단언
                // 무음 통과 아님(null==null 버그 회귀 차단)
                .andExpect(model().attributeHasFieldErrors("passwordResetConfirmForm", "newPasswordConfirm"))
                // 비번 clear 단언
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPassword", nullValue())))
                .andExpect(model().attribute("passwordResetConfirmForm",
                        hasProperty("newPasswordConfirm", nullValue())));

        verify(passwordResetFacade, never()).confirmReset(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /password-reset/confirm 검증 실패 시 resetTokenValid=true 로 재렌더 (폼 표시 유지)")
    void post_confirm_validationFailure_rerenders_with_resetTokenValid_true() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", VALID_TOKEN)
                        .param("newPassword", "short")
                        .param("newPasswordConfirm", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/password-reset-confirm"))
                .andExpect(model().attribute("resetTokenValid", true));
    }

    @Test
    @DisplayName("POST /password-reset/confirm CSRF 없음 → 403")
    void post_confirm_withoutCsrf_returns_403() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", VALID_TOKEN)
                        .param("newPassword", NEW_PW)
                        .param("newPasswordConfirm", NEW_PW))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // login.html — /password-reset 링크 렌더 단언
    // ============================================================

    @Test
    @DisplayName("GET /login → login.html에 /password-reset 링크 렌더")
    void get_login_contains_password_reset_link() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("/password-reset")));
    }
}
