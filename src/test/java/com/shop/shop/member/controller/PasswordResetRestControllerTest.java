package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.InvalidPasswordResetTokenException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.service.PasswordResetServiceResponse;
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

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PasswordResetRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - /request: 비로그인 허용(permitAll), 존재/미존재 이메일 모두 200 + 동일 본문(enumeration 방지)
 * - /request: @Valid 이메일 형식 오류 → 400
 * - /confirm: 비로그인 허용(permitAll)
 * - /confirm: 무효 토큰 → 400 ErrorResponse
 * - /confirm: 비밀번호 불일치(newPasswordConfirm 필드 에러) → 400
 * - /confirm: 토큰 값이 응답 본문에 포함되지 않음
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({FakeRefreshTokenStore.class, FakePasswordResetTokenStore.class})
class PasswordResetRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PasswordResetServiceResponse passwordResetServiceResponse;

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

    // ──────────────────────────────────────────────────────────────────────────
    // /request — permitAll + 200 동일 본문 (enumeration 방지)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/request — 비로그인 접근 허용(permitAll), 존재 이메일 200")
    void request_비로그인허용_존재이메일_200() throws Exception {
        doNothing().when(passwordResetServiceResponse).request(any());

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "user@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("비밀번호 재설정")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/request — 미존재 이메일도 200 + 동일 본문(enumeration 방지)")
    void request_미존재이메일_200_동일본문() throws Exception {
        doNothing().when(passwordResetServiceResponse).request(any());

        String responseBody1 = mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "exists@example.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String responseBody2 = mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "notexists@example.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 두 응답이 동일 본문이어야 함 (enumeration 방지)
        org.assertj.core.api.Assertions.assertThat(responseBody1).isEqualTo(responseBody2);
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/request — 이메일 형식 오류 → 400")
    void request_이메일형식오류_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "invalid-email"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/request — 이메일 blank → 400")
    void request_이메일blank_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", ""))))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // /confirm — permitAll + 400 시나리오
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/confirm — 비로그인 접근 허용(permitAll), 유효 토큰 200")
    void confirm_비로그인허용_유효토큰_200() throws Exception {
        doNothing().when(passwordResetServiceResponse).confirm(any());

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "validtoken123",
                                "newPassword", "newPass1234",
                                "newPasswordConfirm", "newPass1234"
                        ))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/confirm — 무효/만료/사용 토큰 → 400 ErrorResponse")
    void confirm_무효토큰_400() throws Exception {
        doThrow(new InvalidPasswordResetTokenException())
                .when(passwordResetServiceResponse).confirm(any());

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "expiredtoken",
                                "newPassword", "newPass1234",
                                "newPasswordConfirm", "newPass1234"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("유효하지 않거나 만료된")))
                // 토큰 값이 응답 본문에 포함되지 않아야 함
                .andExpect(jsonPath("$.message").value(not(containsString("expiredtoken"))));
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/confirm — 비밀번호 불일치 → 400, newPasswordConfirm 필드 에러")
    void confirm_비밀번호불일치_400_newPasswordConfirm에보고() throws Exception {
        // newPassword != newPasswordConfirm (실값으로 제공 — null==null 무음 통과 우회)
        // @PasswordMatches(field="newPassword", confirmField="newPasswordConfirm") 일반화 실동작 검증:
        // validator가 newPasswordConfirm 필드에 위반을 보고하고(PasswordMatchesValidator.addPropertyNode),
        // RestExceptionHandler가 FieldError.getDefaultMessage()를 message 필드에 담는다.
        // 현 ErrorResponse 포맷(status/message/path/timestamp)에서 필드명은 message에 포함된 메시지로 확인.
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "anytoken",
                                "newPassword", "newPass1234",
                                "newPasswordConfirm", "differentPass9"
                        ))))
                .andExpect(status().isBadRequest())
                // status 400 단언
                .andExpect(jsonPath("$.status").value(400))
                // @PasswordMatches 위반 메시지 포함 — 무음 통과(null==null) 아님을 회귀 차단
                .andExpect(jsonPath("$.message").value(containsString("비밀번호가 일치하지 않습니다.")))
                // path 필드 존재 확인 (ErrorResponse 구조 회귀)
                .andExpect(jsonPath("$.path").value(containsString("/api/v1/auth/password-reset/confirm")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset/confirm — 비밀번호 최소 길이 미달 → 400")
    void confirm_비밀번호최소길이미달_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "anytoken",
                                "newPassword", "short",
                                "newPasswordConfirm", "short"
                        ))))
                .andExpect(status().isBadRequest());
    }
}
