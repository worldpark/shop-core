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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 활동 추적(last_login_at) 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>054 변경사항: formLogin 제거 → View 로그인은 POST /login (CookieLoginViewController).
 * last_login_at은 ViewAuthService.loginAndSetCookies → recordLoginByEmail 명시 호출로 보장.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>REST 로그인 성공 후 last_login_at 갱신 (기존 무변경)</li>
 *   <li>View 폼 로그인(POST /login) 성공 후 last_login_at 갱신 (formLogin 이벤트 대체 회귀 가드)</li>
 *   <li>REST 로그인 실패 시 last_login_at 미갱신</li>
 *   <li>View 폼 로그인 실패 시 last_login_at 미갱신</li>
 * </ul>
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
    private PasswordEncoder passwordEncoder;

    private static final String EMAIL = "login-activity@example.com";
    private static final String PASSWORD = "test-password-123";

    @BeforeEach
    void setUp() {
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
    // View 폼 로그인 성공 → last_login_at 갱신 (formLogin 이벤트 대체 회귀 가드)
    // ============================================================

    @Test
    @DisplayName("View 폼 로그인(POST /login) 성공 후 last_login_at 갱신됨 — formLogin 이벤트 대체 회귀 가드")
    void view_form_login_success_updates_last_login_at() throws Exception {
        Instant before = Instant.now();

        // ViewAuthService.loginAndSetCookies → recordLoginByEmail 명시 호출
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", EMAIL)
                        .param("password", PASSWORD))
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
    // View 폼 로그인 실패 → last_login_at 미갱신
    // ============================================================

    @Test
    @DisplayName("View 폼 로그인 실패(잘못된 비밀번호) 시 last_login_at 미갱신")
    void view_form_login_fail_does_not_update_last_login_at() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", EMAIL)
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection()); // /login?error 로 리다이렉트

        User user = memberRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getLastLoginAt()).isNull();
    }

    // ============================================================
    // View 폼 로그인 성공 → 302 redirect 보존
    // ============================================================

    @Test
    @DisplayName("회귀: View 폼 로그인(POST /login) 성공 후 302 리다이렉트 보존")
    void view_form_login_success_preserves_302_redirect() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());
    }
}
