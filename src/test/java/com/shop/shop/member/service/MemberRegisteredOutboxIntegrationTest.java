package com.shop.shop.member.service;

import com.shop.shop.member.event.MemberRegisteredEvent;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원가입 Outbox 발행 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>가입 커밋 시 MemberRegisteredEvent 1건 발행 + 페이로드 필수 필드(memberId/memberEmail/memberName) 검증</li>
 *   <li>롤백(중복 이메일) 시 이벤트 미발행 (0건)</li>
 * </ul>
 *
 * <p>Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 * 이벤트 캡처: CaptureListener @TransactionalEventListener(AFTER_COMMIT).
 * OrderCancellationOutboxIntegrationTest 패턴 계승.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MemberRegisteredOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class MemberRegisteredOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptureListener captureListener;

    @BeforeEach
    void setUp() {
        captureListener.clear();
    }

    // ============================================================
    // 가입 커밋 + Outbox 발행
    // ============================================================

    @Test
    @DisplayName("가입 커밋 시 MemberRegisteredEvent 1건 발행 + 페이로드 필수 필드 검증")
    void signup_commit_publishesMemberRegisteredEvent() {
        // given
        String email = "outbox-member1@test.com";
        String name = "신규회원";

        // when
        memberService.signup(email, "password123!", name, null);

        // then — AFTER_COMMIT 캡처
        List<MemberRegisteredEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);

        MemberRegisteredEvent event = events.get(0);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.memberId()).isPositive();
        assertThat(event.memberEmail()).isEqualTo(email);
        assertThat(event.memberName()).isEqualTo(name);
    }

    // ============================================================
    // 롤백 시 미발행
    // ============================================================

    @Test
    @DisplayName("중복 이메일(DuplicateEmailException) 롤백 시 MemberRegisteredEvent 미발행 (0건)")
    void signup_duplicateEmail_rollback_doesNotPublishEvent() {
        // given — 같은 이메일로 먼저 가입 성공
        String email = "outbox-dup@test.com";
        memberService.signup(email, "password123!", "첫번째회원", null);
        captureListener.clear(); // 첫 번째 가입 이벤트 초기화

        // when — 중복 이메일로 재가입 시도 → DuplicateEmailException
        assertThatThrownBy(() -> memberService.signup(email, "password456!", "두번째회원", null));

        // then — 이벤트 미발행
        assertThat(captureListener.getEvents()).isEmpty();
    }

    // ============================================================
    // 테스트 전용 이벤트 리스너
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<MemberRegisteredEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(MemberRegisteredEvent event) {
            events.add(event);
        }

        public List<MemberRegisteredEvent> getEvents() {
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
