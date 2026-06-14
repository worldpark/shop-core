package com.shop.shop.member.service;

import com.shop.shop.member.event.PasswordResetRequestedEvent;
import com.shop.shop.security.support.FakePasswordResetTokenStore;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 재설정 Outbox 발행 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>requestReset(존재 이메일) 커밋 시 PasswordResetRequestedEvent 1건 발행 + 페이로드 필드
 *       (memberId/memberEmail/memberName/resetUrl 토큰 포함·접두 baseUrl/expiresAt non-null) 검증</li>
 *   <li>requestReset(미존재/탈퇴 이메일) 시 0건 (enumeration·no-op)</li>
 * </ul>
 *
 * <p>Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 * 이벤트 캡처: CaptureListener @TransactionalEventListener(AFTER_COMMIT).
 * 토큰 스토어: FakePasswordResetTokenStore @Import 사용 (Redis 불요).
 * MemberRegisteredOutboxIntegrationTest 패턴 계승.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
        PasswordResetOutboxIntegrationTest.CaptureListener.class,
        FakePasswordResetTokenStore.class,
        FakeRefreshTokenStore.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.app.base-url=http://localhost:8080"
})
class PasswordResetOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MemberService memberService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptureListener captureListener;

    @BeforeEach
    void setUp() {
        captureListener.clear();
    }

    // ============================================================
    // 존재 이메일 requestReset → 이벤트 1건 발행 + 페이로드 검증
    // ============================================================

    @Test
    @DisplayName("requestReset(존재 이메일) 커밋 시 PasswordResetRequestedEvent 1건 발행 + 페이로드 필드 검증")
    void requestReset_존재이메일_커밋시_이벤트1건_페이로드검증() {
        // given — 활성 회원 생성
        String email = "outbox-reset1@test.com";
        String name = "재설정회원";
        memberService.signup(email, "password123!", name, null);
        captureListener.clear(); // 가입 이벤트 초기화

        // when
        passwordResetService.requestReset(email);

        // then — AFTER_COMMIT 캡처
        List<PasswordResetRequestedEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);

        PasswordResetRequestedEvent event = events.get(0);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.memberId()).isPositive();
        assertThat(event.memberEmail()).isEqualTo(email);
        assertThat(event.memberName()).isEqualTo(name);

        // resetUrl: baseUrl 접두 + 토큰 포함
        assertThat(event.resetUrl()).startsWith("http://localhost:8080");
        assertThat(event.resetUrl()).contains("/password-reset/confirm?token=");
        String tokenInUrl = event.resetUrl().split("token=")[1];
        assertThat(tokenInUrl).isNotBlank();

        // expiresAt non-null
        assertThat(event.expiresAt()).isNotNull();
    }

    // ============================================================
    // 미존재 이메일 requestReset → 0건 (enumeration 방지)
    // ============================================================

    @Test
    @DisplayName("requestReset(미존재 이메일) 시 PasswordResetRequestedEvent 0건 (enumeration·no-op)")
    void requestReset_미존재이메일_이벤트0건() {
        // given — 존재하지 않는 이메일
        String nonExistentEmail = "nobody-reset@test.com";

        // when
        passwordResetService.requestReset(nonExistentEmail);

        // then — 이벤트 미발행
        assertThat(captureListener.getEvents()).isEmpty();
    }

    // ============================================================
    // 탈퇴(소프트삭제) 이메일 requestReset → 0건 (no-op)
    // ============================================================

    @Test
    @DisplayName("requestReset(탈퇴 이메일) 시 PasswordResetRequestedEvent 0건 (findActiveByEmail empty)")
    void requestReset_탈퇴이메일_이벤트0건() {
        // given — 회원 생성 후 소프트삭제(탈퇴)
        String email = "withdrawn-reset1@test.com";
        memberService.signup(email, "password123!", "탈퇴회원", null);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        accountService.withdraw(userId);
        captureListener.clear(); // 가입 이벤트 초기화

        // when — 탈퇴 이메일로 재설정 요청 (findActiveByEmail empty → no-op)
        passwordResetService.requestReset(email);

        // then — 이벤트 미발행
        assertThat(captureListener.getEvents()).isEmpty();
    }

    // ============================================================
    // 테스트 전용 이벤트 리스너
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<PasswordResetRequestedEvent> events =
                Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(PasswordResetRequestedEvent event) {
            events.add(event);
        }

        public List<PasswordResetRequestedEvent> getEvents() {
            return Collections.unmodifiableList(events);
        }

        public int size() {
            return events.size();
        }

        public void clear() {
            events.clear();
        }
    }
}
