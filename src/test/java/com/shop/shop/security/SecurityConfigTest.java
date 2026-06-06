package com.shop.shop.security;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SecurityConfig View 체인 통합 테스트.
 * 시나리오 (a)~(e) + CSRF 활성 단언.
 *
 * <p>인증 소스 변경: InMemoryUserDetailsManager → MemberUserDetailsService (DB 기반).
 * MemberUserDetailsService를 @MockitoBean으로 stub하여 실 DB 없이 테스트.
 *
 * <p>RefreshTokenStore는 FakeRefreshTokenStore로 교체 (Redis 미기동 환경 비파괴).
 *
 * <p>View 체인 동작(302 redirect, /login 200, CSRF 403)은 그대로 유지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SecurityConfigTest {

    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "dev1234";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * MemberUserDetailsService stub 초기화.
     * BCrypt 인코딩된 비밀번호로 사용자를 반환하도록 설정.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        String encodedPassword = passwordEncoder.encode(PASSWORD);
        User stubUser = new User(
                USERNAME,
                encodedPassword,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))
        );
        when(memberUserDetailsService.loadUserByUsername(USERNAME)).thenReturn(stubUser);
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
    @DisplayName("(c) 올바른 자격증명으로 폼 로그인 성공 → authenticated + 302 to /")
    void form_login_with_correct_credentials_succeeds() throws Exception {
        mockMvc.perform(formLogin("/login").user(USERNAME).password(PASSWORD))
                .andExpect(authenticated())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("(d) 잘못된 비밀번호로 폼 로그인 실패 → unauthenticated + 302 to /login?error")
    void form_login_with_wrong_password_fails() throws Exception {
        mockMvc.perform(formLogin("/login").user(USERNAME).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("(e) 인증 상태에서 로그아웃 → 302 to /login?logout")
    void logout_redirects_to_login_with_logout_param() throws Exception {
        mockMvc.perform(logout("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    @DisplayName("(보강) CSRF 토큰 없이 POST /login 요청 시 403 반환 — CSRF 활성 단언")
    void post_login_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }
}
