package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberService;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemberRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - POST /api/v1/members/signup 성공 201 + body memberId/email/name/role
 * - POST /api/v1/members/signup 응답에 password/passwordHash 미포함
 * - POST /api/v1/members/signup 검증 실패 400
 * - POST /api/v1/members/signup 중복 이메일 409
 * - GET /api/v1/members/me CONSUMER/SELLER/ADMIN 유효 Bearer → 200
 * - GET /api/v1/members/me 비인증 → 401 JSON (redirect 아님)
 * - GET /api/v1/members/me 위조 토큰 → 401 JSON
 * - GET /api/v1/members/me logout 후 blacklist access token → 401
 *
 * <p>FakeRefreshTokenStore: Redis 미기동 환경 비파괴.
 * MemberRepository: @MockitoBean stub.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class MemberRestControllerTest {

    private static final String EMAIL = "newuser@example.com";
    private static final String PASSWORD = "password123";
    private static final String NAME = "홍길동";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FakeRefreshTokenStore fakeRefreshTokenStore;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberService memberService;

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

    private User testUser;

    @BeforeEach
    void setUp() {
        fakeRefreshTokenStore.clear();

        testUser = User.of(EMAIL, "$2a$10$hashedpw", NAME, null, Role.CONSUMER);
        setUserId(testUser, 1L);

        when(memberService.signup(EMAIL, PASSWORD, NAME, null)).thenReturn(testUser);
        when(memberService.getById(1L)).thenReturn(testUser);
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 성공 → 201 + body(memberId/email/name/role)")
    void signup_success_returns_201_with_body() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new SignupRequestBody(EMAIL, PASSWORD, PASSWORD, NAME, null));

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(1L))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.role").value("CONSUMER"));
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 응답에 password/passwordHash 미포함")
    void signup_response_does_not_contain_password() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new SignupRequestBody(EMAIL, PASSWORD, PASSWORD, NAME, null));

        String responseBody = mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 민감정보 미포함 단언
        org.assertj.core.api.Assertions.assertThat(responseBody)
                .doesNotContain("\"password\"")
                .doesNotContain("\"passwordHash\"")
                .doesNotContain("\"password_hash\"");
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 이메일 형식 오류 → 400 ErrorResponse JSON")
    void signup_invalid_email_returns_400() throws Exception {
        String requestBody = """
                {"email": "not-an-email", "password": "password123", "passwordConfirm": "password123", "name": "홍길동"}
                """;

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 비밀번호 불일치 → 400 ErrorResponse JSON")
    void signup_password_mismatch_returns_400() throws Exception {
        String requestBody = """
                {"email": "user@example.com", "password": "password123", "passwordConfirm": "different", "name": "홍길동"}
                """;

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 비밀번호 최소 길이 위반 → 400 ErrorResponse JSON")
    void signup_password_too_short_returns_400() throws Exception {
        String requestBody = """
                {"email": "user@example.com", "password": "short", "passwordConfirm": "short", "name": "홍길동"}
                """;

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/members/signup 중복 이메일 → 409 ErrorResponse JSON")
    void signup_duplicate_email_returns_409() throws Exception {
        when(memberService.signup(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateEmailException());

        String requestBody = objectMapper.writeValueAsString(
                new SignupRequestBody(EMAIL, PASSWORD, PASSWORD, NAME, null));

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — 유효한 Bearer access token → 200 + MeResponse")
    void me_with_valid_bearer_token_returns_200() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(
                1L, testUser.getEmail(), List.of(testUser.getRole().authority()));

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — 비인증(토큰 없음) → 401 JSON (redirect 아님)")
    void me_without_token_returns_401_json() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — SELLER 유효 Bearer → 200 (RoleHierarchy: SELLER > CONSUMER)")
    void me_with_seller_token_returns_200() throws Exception {
        User sellerUser = User.of("seller@example.com", "$2a$10$hashedpw", "판매자", null, Role.SELLER);
        setUserId(sellerUser, 2L);
        when(memberService.getById(2L)).thenReturn(sellerUser);

        String accessToken = jwtTokenProvider.createAccess(
                2L, sellerUser.getEmail(), List.of(sellerUser.getRole().authority()));

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("seller@example.com"))
                .andExpect(jsonPath("$.role").value("SELLER"));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — ADMIN 유효 Bearer → 200 (RoleHierarchy: ADMIN > SELLER > CONSUMER)")
    void me_with_admin_token_returns_200() throws Exception {
        User adminUser = User.of("admin@example.com", "$2a$10$hashedpw", "관리자", null, Role.ADMIN);
        setUserId(adminUser, 3L);
        when(memberService.getById(3L)).thenReturn(adminUser);

        String accessToken = jwtTokenProvider.createAccess(
                3L, adminUser.getEmail(), List.of(adminUser.getRole().authority()));

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — 위조된 토큰(Bearer invalid.jwt.token) → 401 JSON")
    void me_with_tampered_token_returns_401_json() throws Exception {
        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/members/me — logout 후 blacklist된 access token → 401")
    void me_with_blacklisted_access_returns_401() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(
                1L, testUser.getEmail(), List.of(testUser.getRole().authority()));
        String refreshToken = jwtTokenProvider.createRefresh(1L);
        fakeRefreshTokenStore.storeRefresh(1L, refreshToken, java.time.Duration.ofDays(14));

        // logout — access token을 blacklist에 등록
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // blacklist된 access token으로 /me 접근 → 401
        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/members/signup — 공개 API (토큰 없이 접근 가능)")
    void signup_is_public_no_auth_required() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new SignupRequestBody(EMAIL, PASSWORD, PASSWORD, NAME, null));

        // 토큰 없이 201이 반환되어야 함 (401이 아님)
        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());
    }

    // helpers

    private void setUserId(User user, long id) {
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 테스트용 JSON 직렬화 헬퍼 record */
    record SignupRequestBody(String email, String password, String passwordConfirm, String name, String phone) {}
}
