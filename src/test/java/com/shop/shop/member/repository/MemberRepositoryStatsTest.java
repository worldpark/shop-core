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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemberRepository 통계 파생 쿼리 통합 테스트 (실 PostgreSQL).
 *
 * <p>Task 043 §1.1 — 관리자 통계 대시보드 유저 이용률 쿼리 검증.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>countByStatus(ACTIVE) — ACTIVE/WITHDRAWN 필터 정확성</li>
 *   <li>countByStatusAndLastLoginAtAfter — 30일 경계 직전/직후 포함·제외</li>
 *   <li>lastLoginAt=null인 회원 미포함</li>
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
class MemberRepositoryStatsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TestEntityManager em;

    // ============================================================
    // countByStatus
    // ============================================================

    @Test
    @DisplayName("countByStatus(ACTIVE) — ACTIVE 회원만 집계, WITHDRAWN 제외")
    void countByStatus_active_excludes_withdrawn() {
        // ACTIVE 2명, WITHDRAWN 1명 저장
        memberRepository.save(User.of("a1@ex.com", "hash", "A1", null, Role.CONSUMER));
        memberRepository.save(User.of("a2@ex.com", "hash", "A2", null, Role.SELLER));
        User withdrawn = User.of("w1@ex.com", "hash", "W1", null, Role.CONSUMER);
        withdrawn.withdraw();
        memberRepository.save(withdrawn);
        em.flush();
        em.clear();

        long count = memberRepository.countByStatus(MemberStatus.ACTIVE);

        // ACTIVE 2명만 집계
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countByStatus(WITHDRAWN) — WITHDRAWN 회원만 집계")
    void countByStatus_withdrawn_counts_only_withdrawn() {
        User withdrawn1 = User.of("w2@ex.com", "hash", "W2", null, Role.CONSUMER);
        withdrawn1.withdraw();
        memberRepository.save(withdrawn1);
        memberRepository.save(User.of("a3@ex.com", "hash", "A3", null, Role.CONSUMER));
        em.flush();
        em.clear();

        long count = memberRepository.countByStatus(MemberStatus.WITHDRAWN);

        assertThat(count).isEqualTo(1L);
    }

    // ============================================================
    // countByStatusAndLastLoginAtAfter — 경계 검증
    // ============================================================

    @Test
    @DisplayName("countByStatusAndLastLoginAtAfter — threshold 이후 로그인한 ACTIVE 회원만 집계")
    void countByStatusAndLastLoginAtAfter_counts_active_logged_in_after_threshold() {
        Instant now = Instant.now();
        Instant threshold = now.minus(30, ChronoUnit.DAYS);

        // 30일 이내 로그인 — 집계 포함 대상
        User recentLogin = User.of("recent@ex.com", "hash", "Recent", null, Role.CONSUMER);
        recentLogin.recordLogin(now.minus(1, ChronoUnit.DAYS));
        memberRepository.save(recentLogin);

        // 30일 경계(threshold)보다 1초 이후 — 포함
        User boundaryInclude = User.of("boundary-in@ex.com", "hash", "BoundaryIn", null, Role.CONSUMER);
        boundaryInclude.recordLogin(threshold.plusSeconds(1));
        memberRepository.save(boundaryInclude);

        // 30일 경계(threshold)보다 1초 이전 — 제외
        User boundaryExclude = User.of("boundary-out@ex.com", "hash", "BoundaryOut", null, Role.CONSUMER);
        boundaryExclude.recordLogin(threshold.minusSeconds(1));
        memberRepository.save(boundaryExclude);

        em.flush();
        em.clear();

        long count = memberRepository.countByStatusAndLastLoginAtAfter(MemberStatus.ACTIVE, threshold);

        // recentLogin + boundaryInclude = 2명
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countByStatusAndLastLoginAtAfter — lastLoginAt=null 회원 제외")
    void countByStatusAndLastLoginAtAfter_excludes_null_last_login() {
        Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);

        // 로그인 기록 없음(null)
        memberRepository.save(User.of("never-logged@ex.com", "hash", "NeverLogged", null, Role.CONSUMER));
        // 최근 로그인
        User active = User.of("logged@ex.com", "hash", "Logged", null, Role.CONSUMER);
        active.recordLogin(Instant.now().minus(5, ChronoUnit.DAYS));
        memberRepository.save(active);
        em.flush();
        em.clear();

        long count = memberRepository.countByStatusAndLastLoginAtAfter(MemberStatus.ACTIVE, threshold);

        // lastLoginAt=null은 제외 → 1명만
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("countByStatusAndLastLoginAtAfter — WITHDRAWN 회원은 ACTIVE 필터로 제외")
    void countByStatusAndLastLoginAtAfter_excludes_withdrawn_members() {
        Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);

        // WITHDRAWN이지만 최근 로그인 기록 있음 — 제외되어야 함
        User withdrawn = User.of("withdrawn-recent@ex.com", "hash", "WRecentLogin", null, Role.CONSUMER);
        withdrawn.recordLogin(Instant.now().minus(1, ChronoUnit.DAYS));
        withdrawn.withdraw();
        memberRepository.save(withdrawn);
        em.flush();
        em.clear();

        long count = memberRepository.countByStatusAndLastLoginAtAfter(MemberStatus.ACTIVE, threshold);

        assertThat(count).isEqualTo(0L);
    }

    @Test
    @DisplayName("countByStatusAndLastLoginAtAfter — 30일 직전 경계 확인 (threshold 이전은 제외)")
    void countByStatusAndLastLoginAtAfter_boundary_just_before_threshold_is_excluded() {
        Instant now = Instant.now();
        Instant threshold = now.minus(30, ChronoUnit.DAYS);

        // 정확히 threshold — JPQL은 lastLoginAt > threshold (After = strictly after)
        // 따라서 threshold 시각 그 자체는 포함되지 않는다
        User atThreshold = User.of("at-threshold@ex.com", "hash", "AtThreshold", null, Role.CONSUMER);
        atThreshold.recordLogin(threshold);
        memberRepository.save(atThreshold);
        em.flush();
        em.clear();

        long count = memberRepository.countByStatusAndLastLoginAtAfter(MemberStatus.ACTIVE, threshold);

        // Spring Data JPA의 After 파생 쿼리 = > (strictly after), threshold 자체는 제외
        assertThat(count).isEqualTo(0L);
    }
}
