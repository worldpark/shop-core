package com.shop.shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.LoginRequest;
import com.shop.shop.member.repository.MemberRepository;
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
import com.shop.shop.order.repository.OrderRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - login 성공(200 + access/refresh), 실패(401 ErrorResponse JSON)
 * - login @Valid 검증 실패 400
 * - refresh 정상 → 200
 * - logout 후 동일 refresh → 401
 *
 * <p>FakeRefreshTokenStore: Redis 미기동 환경 비파괴 (@Import + @Primary).
 * MemberRepository: @MockitoBean으로 stub (실 DB 미사용).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AuthRestControllerSecurityTest {

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "test-password-123";

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() throws Exception {
        fakeRefreshTokenStore.clear();

        testUser = User.of(EMAIL, passwordEncoder.encode(PASSWORD), "테스터", null, Role.CONSUMER);
        setUserId(testUser, 1L);

        when(memberService.authenticate(eq(EMAIL), eq(PASSWORD))).thenReturn(testUser);
        when(memberService.getById(1L)).thenReturn(testUser);
    }

    @Test
    @DisplayName("POST /api/v1/auth/login 성공 → 200 + access/refresh token 발급")
    void login_success_returns_200_with_tokens() throws Exception {
        LoginRequest request = new LoginRequest(EMAIL, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login 실패 (잘못된 비밀번호) → 401 ErrorResponse JSON")
    void login_fail_returns_401_error_response() throws Exception {
        when(memberService.authenticate(eq(EMAIL), eq("wrong-pw")))
                .thenThrow(new com.shop.shop.common.exception.InvalidCredentialsException());

        LoginRequest request = new LoginRequest(EMAIL, "wrong-pw");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh — 유효한 refresh token → 200 + 새 access token")
    void refresh_with_valid_token_returns_200() throws Exception {
        // 로그인하여 refresh token 획득
        String refreshToken = loginAndGetRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout 후 동일 refresh token → 401")
    void logout_then_refresh_returns_401() throws Exception {
        String accessToken = jwtTokenProvider.createAccess(
                1L, testUser.getEmail(), List.of(testUser.getRole().authority()));
        String refreshToken = jwtTokenProvider.createRefresh(1L);
        fakeRefreshTokenStore.storeRefresh(1L, refreshToken,
                java.time.Duration.ofDays(14));

        // logout
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // 동일 refresh로 재발급 시도
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login — @Valid 검증 실패 (이메일 형식 오류) → 400 JSON")
    void login_invalid_email_returns_400() throws Exception {
        String invalidRequest = "{\"email\": \"not-an-email\", \"password\": \"password123\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // helpers

    private String loginAndGetRefreshToken() throws Exception {
        LoginRequest request = new LoginRequest(EMAIL, PASSWORD);
        String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseBody).get("refreshToken").asText();
    }

    private void setUserId(User user, long id) {
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
