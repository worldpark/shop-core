package com.shop.shop.view;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.MemberSignupFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원가입·로그인 화면 렌더링 통합 테스트.
 *
 * <p>실제 main 템플릿(templates/member/signup.html, templates/auth/login.html)이
 * layout/blank 및 fragments/footer와 함께 올바르게 렌더링되는지 검증한다.
 *
 * <p>테스트 컨벤션: LayoutRenderingTest 패턴 준수.
 * - @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")
 * - @Import(FakeRefreshTokenStore) + @MockBean MemberRepository, MemberUserDetailsService
 * - 본문 마커 단언 (assertThat(body).contains(...))
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>(S1) GET /signup — 익명(permitAll) → 200, 폼 action=/signup, 필드(email/password/passwordConfirm/name/phone),
 *       _csrf 자동 주입, 로그인 링크(/login), footer 마커</li>
 *   <li>(S2) GET /login?signup — 200, param.signup 안내 메시지 + 회원가입 링크(/signup) 포함</li>
 *   <li>(S3) GET /login?signup — login 기존 요소 비파괴: name=username, _csrf, footer 마커</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SignupRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private MemberSignupFacade memberSignupFacade;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private ProductOptionRepository productOptionRepository;

    @MockBean
    private OptionValueRepository optionValueRepository;

    @MockBean
    private ProductVariantRepository productVariantRepository;

    /** footer 프래그먼트 식별 마커 */
    static final String FOOTER_MARKER = "2026 shop-core. All rights reserved.";

    @Test
    @DisplayName("(S1) GET /signup — 익명 접근 → 200, 폼 action/필드/csrf/_csrf/로그인 링크/footer 포함")
    void signup_page_renders_form_with_all_fields() throws Exception {
        String body = mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 폼 action="/signup" (th:action 처리 후 렌더 결과)
        assertThat(body).as("폼 action이 /signup 여야 함")
                .contains("action=\"/signup\"");

        // 폼 method="post"
        assertThat(body).as("폼 method가 post 여야 함")
                .contains("method=\"post\"");

        // 필드: email
        assertThat(body).as("email 필드가 렌더링되어야 함")
                .contains("name=\"email\"");

        // 필드: password
        assertThat(body).as("password 필드가 렌더링되어야 함")
                .contains("name=\"password\"");

        // 필드: passwordConfirm
        assertThat(body).as("passwordConfirm 필드가 렌더링되어야 함")
                .contains("name=\"passwordConfirm\"");

        // 필드: name
        assertThat(body).as("name 필드가 렌더링되어야 함")
                .contains("name=\"name\"");

        // 필드: phone
        assertThat(body).as("phone 필드가 렌더링되어야 함")
                .contains("name=\"phone\"");

        // CSRF 토큰 자동 주입 (th:action 자동)
        assertThat(body).as("_csrf 히든 필드가 자동 주입되어야 함")
                .contains("_csrf");

        // 로그인 링크
        assertThat(body).as("로그인 링크(/login)가 포함되어야 함")
                .contains("/login");

        // footer 마커 (layout/blank → fragments/footer)
        assertThat(body).as("footer 마커가 포함되어야 함")
                .contains(FOOTER_MARKER);
    }

    @Test
    @DisplayName("(S2) GET /login?signup — 200, param.signup 안내 메시지 + 회원가입 링크(/signup) 포함")
    void login_page_with_signup_param_shows_success_message_and_signup_link() throws Exception {
        String body = mockMvc.perform(get("/login").param("signup", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // param.signup 안내 메시지
        assertThat(body).as("회원가입 완료 안내 메시지가 표시되어야 함")
                .contains("회원가입이 완료되었습니다. 로그인해 주세요.");

        // 회원가입 링크
        assertThat(body).as("회원가입 링크(/signup)가 포함되어야 함")
                .contains("/signup");
    }

    @Test
    @DisplayName("(S3) GET /login?signup — 기존 요소 비파괴: name=username, _csrf, footer 마커 유지")
    void login_page_with_signup_param_preserves_existing_elements() throws Exception {
        String body = mockMvc.perform(get("/login").param("signup", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // name=username 비파괴 (Spring Security UsernamePasswordAuthenticationFilter)
        assertThat(body).as("name=username 필드가 유지되어야 함 (비파괴)")
                .contains("name=\"username\"");

        // _csrf 비파괴
        assertThat(body).as("_csrf 토큰이 유지되어야 함 (비파괴)")
                .contains("_csrf");

        // footer 마커 비파괴
        assertThat(body).as("footer 마커가 유지되어야 함 (비파괴)")
                .contains(FOOTER_MARKER);
    }
}
