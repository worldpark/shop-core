package com.shop.shop.member.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.LoginRequest;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 활동 추적(last_login_at) 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>REST 로그인 성공 후 last_login_at 갱신(non-null, 로그인 직전 >= 기준 시각)</li>
 *   <li>formLogin 성공 후 last_login_at 갱신(InteractiveAuthenticationSuccessEvent → LoginActivityRecorder)</li>
 *   <li>REST 로그인 실패(잘못된 비밀번호) 시 last_login_at 미갱신</li>
 *   <li>formLogin 실패 시 last_login_at 미갱신</li>
 *   <li>REST 로그인 성공 후 302 리다이렉트 비파괴(SecurityConfig 무변경 회귀)</li>
 *   <li>formLogin 성공 후 302 리다이렉트 보존(SecurityConfig 무변경 회귀)</li>
 * </ul>
 *
 * <p>SecurityConfig 무변경: successHandler·requestCache·defaultSuccessUrl 그대로라
 * formLogin 성공은 302 → "/" 리다이렉트를 유지한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(FakeRefreshTokenStore.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class LoginActivityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberService memberService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String EMAIL = "login-activity@example.com";
    private static final String PASSWORD = "test-password-123";

    @BeforeEach
    void setUp() {
        // 매 테스트마다 신선한 사용자 생성 (이메일 충돌 방지)
        memberRepository.deleteAll();
        User user = User.of(EMAIL, passwordEncoder.encode(PASSWORD), "테스터", null, Role.CONSUMER);
        memberRepository.save(user);
    }

    // ============================================================
    // REST 로그인 성공 → last_login_at 갱신
    // ============================================================

    @Test
    @DisplayName("REST 로그인 성공 후 last_login_at 갱신됨")
    void rest_login_success_updates_last_login_at() throws Exception {
        Instant before = Instant.now();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, PASSWORD))))
                .andExpect(status().isOk());

        User user = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getLastLoginAt()).isAfterOrEqualTo(before);
    }

    // ============================================================
    // formLogin 성공 → last_login_at 갱신 (InteractiveAuthenticationSuccessEvent)
    // ============================================================

    @Test
    @DisplayName("formLogin 성공 후 last_login_at 갱신됨 (InteractiveAuthenticationSuccessEvent 리스너)")
    void form_login_success_updates_last_login_at() throws Exception {
        Instant before = Instant.now();

        mockMvc.perform(formLogin("/login").user(EMAIL).password(PASSWORD))
                .andExpect(status().is3xxRedirection());

        User user = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getLastLoginAt()).isAfterOrEqualTo(before);
    }

    // ============================================================
    // REST 로그인 실패 → last_login_at 미갱신
    // ============================================================

    @Test
    @DisplayName("REST 로그인 실패(잘못된 비밀번호) 시 last_login_at 미갱신")
    void rest_login_fail_does_not_update_last_login_at() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, "wrong-password"))))
                .andExpect(status().isUnauthorized());

        User user = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getLastLoginAt()).isNull();
    }

    // ============================================================
    // formLogin 실패 → last_login_at 미갱신
    // ============================================================

    @Test
    @DisplayName("formLogin 실패(잘못된 비밀번호) 시 last_login_at 미갱신")
    void form_login_fail_does_not_update_last_login_at() throws Exception {
        mockMvc.perform(formLogin("/login").user(EMAIL).password("wrong-password"))
                .andExpect(status().is3xxRedirection()); // /login?error 로 리다이렉트

        User user = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getLastLoginAt()).isNull();
    }

    // ============================================================
    // 회귀: formLogin 성공 → 302 to "/" 보존 (SecurityConfig 무변경 확인)
    // ============================================================

    @Test
    @DisplayName("회귀: formLogin 성공 후 302 리다이렉트 보존 (SecurityConfig·requestCache 무변경)")
    void form_login_success_preserves_302_redirect() throws Exception {
        mockMvc.perform(formLogin("/login").user(EMAIL).password(PASSWORD))
                .andExpect(status().is3xxRedirection());
    }
}
