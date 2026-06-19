package com.shop.shop.member.service;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최초 ADMIN 부트스트랩 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>격리 전제: 테스트가 ADMIN을 직접 심지 않는다. {@code @BeforeEach}에서 {@code deleteAll()}로
 * 테이블을 초기화해 다른 테스트가 ADMIN을 남기지 않음을 보장(flaky 방지).
 *
 * <p>검증:
 * <ul>
 *   <li>ADMIN 0명 컨텍스트 → POST /setup/admin → DB에 role=ADMIN 계정 1건 생성</li>
 *   <li>생성된 계정의 {@code role == ADMIN} 단언</li>
 *   <li>성공 응답은 redirect:/login?adminCreated</li>
 * </ul>
 *
 * <p>Kafka 비활성: spring.autoconfigure.exclude + spring.modulith.events.externalization.enabled=false.
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
class AdminBootstrapIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    private static final String ADMIN_EMAIL = "bootstrap-admin@example.com";
    private static final String ADMIN_PASSWORD = "adminpass1";
    private static final String ADMIN_NAME = "최초관리자";

    /**
     * 테이블 초기화 — ADMIN 0명 상태 보장.
     * 테스트가 직접 ADMIN을 심지 않으며, deleteAll()로 다른 테스트의 잔여 데이터를 제거한다.
     */
    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("ADMIN 0명 → POST /setup/admin 성공 → role=ADMIN 계정 1건 DB 영속 + redirect:/login?adminCreated")
    void bootstrapAdmin_createsAdminAccount_andRedirects() throws Exception {
        // 사전 조건: ADMIN이 0명인지 확인
        assertThat(memberRepository.countByRole(Role.ADMIN)).isZero();

        // POST /setup/admin 실행
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", ADMIN_EMAIL)
                        .param("password", ADMIN_PASSWORD)
                        .param("passwordConfirm", ADMIN_PASSWORD)
                        .param("name", ADMIN_NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?adminCreated"));

        // DB에 ADMIN 계정 1건 생성 확인
        assertThat(memberRepository.countByRole(Role.ADMIN)).isEqualTo(1);

        // 생성된 계정의 role=ADMIN 확인
        User admin = memberRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("ADMIN 0명 → POST /setup/admin 성공 → findByEmail의 role=ADMIN (상세 단언)")
    void bootstrapAdmin_accountHasAdminRole() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", ADMIN_EMAIL)
                        .param("password", ADMIN_PASSWORD)
                        .param("passwordConfirm", ADMIN_PASSWORD)
                        .param("name", ADMIN_NAME))
                .andExpect(status().is3xxRedirection());

        User admin = memberRepository.findByEmail(ADMIN_EMAIL).orElseThrow();

        // role=ADMIN 단언
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        // countByRole(ADMIN) == 1 단언
        assertThat(memberRepository.countByRole(Role.ADMIN)).isEqualTo(1);
        // 이메일 정규화 확인
        assertThat(admin.getEmail()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("ADMIN 생성 후 동일 이메일로 재요청 → 이미 이메일 중복으로 폼 재렌더(200) — 멱등성 확인")
    void bootstrapAdmin_duplicateEmailAfterSuccess_rerenderForm() throws Exception {
        // 1차 성공
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", ADMIN_EMAIL)
                        .param("password", ADMIN_PASSWORD)
                        .param("passwordConfirm", ADMIN_PASSWORD)
                        .param("name", ADMIN_NAME))
                .andExpect(status().is3xxRedirection());

        // ADMIN이 이제 1명 존재 → 2차 POST는 AdminAlreadyExistsException → redirect:/login
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", ADMIN_EMAIL)
                        .param("password", ADMIN_PASSWORD)
                        .param("passwordConfirm", ADMIN_PASSWORD)
                        .param("name", ADMIN_NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // ADMIN은 여전히 1명 (중복 생성 안 됨)
        assertThat(memberRepository.countByRole(Role.ADMIN)).isEqualTo(1);
    }
}
