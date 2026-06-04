package com.shop.shop.member.controller;

import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberService;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MemberSignupViewController MockMvc 테스트.
 *
 * <p>참고: GET /signup, login 화면 렌더링(본문 단언)은 member/signup.html이 아직 없으므로
 * 실제 렌더 테스트는 view-implementor 단계로 남긴다.
 * 이 테스트는 컨트롤러 로직·검증·redirect·서비스 호출·비번 clear 위주로 작성.
 *
 * <p>검증 시나리오:
 * - POST /signup 정상(csrf) → 302 redirect:/login?signup, MemberService.signup 호출 검증
 * - POST /signup Bean Validation 실패 → 200 view member/signup, 필드 에러, 서비스 미호출
 * - POST /signup 이메일 중복(DuplicateEmailException) → 200 member/signup, email 필드 에러
 * - POST /signup 비번 필드 clear 검증 (password/passwordConfirm null)
 * - POST /signup CSRF 없음 → 403
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class MemberSignupViewControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "password123";
    private static final String NAME = "홍길동";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberService memberService;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    @Test
    @DisplayName("POST /signup 정상 제출(csrf) → 302 redirect:/login?signup, MemberService.signup 호출")
    void signup_success_redirects_to_login_with_signup_param() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?signup"));

        verify(memberService).signup(EMAIL, PASSWORD, NAME, null);
    }

    @Test
    @DisplayName("POST /signup 이메일 형식 오류 → 200 view member/signup, email 필드 에러, 서비스 미호출")
    void signup_invalid_email_rerenders_form_with_errors() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", "not-an-email")
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "email"));

        verify(memberService, never()).signup(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST /signup 비밀번호 최소 길이 위반 → 200 view member/signup, password 필드 에러")
    void signup_short_password_rerenders_form() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", "short")
                        .param("passwordConfirm", "short")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "password"));

        verify(memberService, never()).signup(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST /signup 비밀번호 불일치 → 200 view member/signup, passwordConfirm 필드 에러")
    void signup_password_mismatch_rerenders_form() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", "different123")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "passwordConfirm"));

        verify(memberService, never()).signup(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST /signup 이메일 중복(DuplicateEmailException) → 200 member/signup, email 필드 에러")
    void signup_duplicate_email_rerenders_form_with_email_error() throws Exception {
        when(memberService.signup(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "email"));
    }

    @Test
    @DisplayName("POST /signup 검증 실패 시 비번 필드 clear (password=null, passwordConfirm=null)")
    void signup_validation_failure_clears_password_fields() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", "not-an-email")
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("password",
                                org.hamcrest.Matchers.nullValue())))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("passwordConfirm",
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("POST /signup 이메일 중복 시 비번 필드 clear")
    void signup_duplicate_email_clears_password_fields() throws Exception {
        when(memberService.signup(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("password",
                                org.hamcrest.Matchers.nullValue())))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("passwordConfirm",
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("POST /signup CSRF 토큰 없음 → 403 Forbidden")
    void signup_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/signup")
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /signup 검증 실패 시 이메일/name은 유지 (비번만 clear)")
    void signup_validation_failure_preserves_email_and_name() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", "short")
                        .param("passwordConfirm", "short")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("member/signup"))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("email",
                                org.hamcrest.Matchers.equalTo(EMAIL))))
                .andExpect(model().attribute("signupForm",
                        org.hamcrest.Matchers.hasProperty("name",
                                org.hamcrest.Matchers.equalTo(NAME))));
    }
}
