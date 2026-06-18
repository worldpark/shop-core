package com.shop.shop.member.repository;

import com.shop.shop.common.crypto.CryptoConfig;
import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.member.domain.MemberStatus;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemberRepository 계정 라이프사이클 통합 테스트 (실 PostgreSQL).
 *
 * <p>V6 마이그레이션 정합(status/deleted_at 컬럼)과
 * findActiveByEmail JPQL 동작을 Testcontainers로 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>V6 적용 — Hibernate validate 정합 (status NOT NULL/length 20, deleted_at nullable)</li>
 *   <li>findActiveByEmail — ACTIVE 단건 반환</li>
 *   <li>findActiveByEmail — WITHDRAWN 미반환</li>
 *   <li>소프트 삭제 후 findByEmail은 여전히 반환(무변경 확인), findActiveByEmail은 empty</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CryptoConfig.class, EnvelopeEncryptionService.class})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class MemberRepositoryAccountTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TestEntityManager em;

    // ============================================================
    // V6 정합 — of()로 생성한 User가 status=ACTIVE로 저장됨
    // ============================================================

    @Test
    @DisplayName("V6 정합 — User 저장 시 status=ACTIVE가 DB에 기록됨")
    void v6_schema_status_field_is_saved_and_retrieved() {
        User user = User.of("test@example.com", "hashed_pw", "테스터", null, Role.CONSUMER);
        memberRepository.save(user);
        em.flush();
        em.clear();

        User found = memberRepository.findById(user.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(found.getDeletedAt()).isNull();
    }

    // ============================================================
    // findActiveByEmail — ACTIVE 단건 반환
    // ============================================================

    @Test
    @DisplayName("findActiveByEmail — ACTIVE 회원을 이메일로 조회하면 반환된다")
    void findActiveByEmail_returns_active_user() {
        User user = User.of("active@example.com", "hashed_pw", "활성", null, Role.CONSUMER);
        memberRepository.save(user);
        em.flush();
        em.clear();

        Optional<User> result = memberRepository.findActiveByEmail("active@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("active@example.com");
        assertThat(result.get().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    // ============================================================
    // findActiveByEmail — WITHDRAWN 미반환
    // ============================================================

    @Test
    @DisplayName("findActiveByEmail — WITHDRAWN 회원은 반환되지 않는다")
    void findActiveByEmail_does_not_return_withdrawn_user() {
        User user = User.of("withdrawn@example.com", "hashed_pw", "탈퇴자", null, Role.CONSUMER);
        user.withdraw();
        memberRepository.save(user);
        em.flush();
        em.clear();

        Optional<User> result = memberRepository.findActiveByEmail("withdrawn@example.com");

        assertThat(result).isEmpty();
    }

    // ============================================================
    // 소프트 삭제 후 findByEmail은 반환, findActiveByEmail은 empty
    // ============================================================

    @Test
    @DisplayName("소프트 삭제 후 findByEmail은 여전히 반환한다(무변경 확인)")
    void findByEmail_returns_withdrawn_user_for_admin_search() {
        User user = User.of("soft-delete@example.com", "hashed_pw", "탈퇴예정자", null, Role.CONSUMER);
        memberRepository.save(user);
        em.flush();

        user.withdraw();
        memberRepository.save(user);
        em.flush();
        em.clear();

        Optional<User> result = memberRepository.findByEmail("soft-delete@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(result.get().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("소프트 삭제 후 findActiveByEmail은 empty 반환")
    void findActiveByEmail_returns_empty_after_soft_delete() {
        User user = User.of("soft-delete2@example.com", "hashed_pw", "탈퇴자2", null, Role.CONSUMER);
        memberRepository.save(user);
        em.flush();

        user.withdraw();
        memberRepository.save(user);
        em.flush();
        em.clear();

        Optional<User> result = memberRepository.findActiveByEmail("soft-delete2@example.com");

        assertThat(result).isEmpty();
    }
}
