package com.shop.shop.member.repository;

import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.SellerApplicationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SellerApplicationRepository} 슬라이스 통합 테스트.
 *
 * <p>실 PostgreSQL(Testcontainers)로 검증:
 * <ul>
 *   <li>부분 유니크 인덱스(uq_seller_application_pending): PENDING 중복 차단</li>
 *   <li>REJECTED 후 재신청(새 PENDING INSERT) 허용</li>
 *   <li>V5 스키마 validate 정합 (Entity ↔ 테이블 타입)</li>
 *   <li>existsByUserIdAndStatus / findFirstByUserIdOrderByCreatedAtDesc / search 쿼리 동작</li>
 * </ul>
 *
 * <p>H2로는 부분 유니크 인덱스(WHERE 절)·CHECK 제약·Postgres 타입을 재현할 수 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SellerApplicationRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private SellerApplicationRepository repository;

    @Autowired
    private TestEntityManager em;

    private long userId1;
    private long userId2;

    @BeforeEach
    void setUp() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('applicant1@test.com', 'x', '신청자1', 'CONSUMER')")
                .executeUpdate();
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('applicant2@test.com', 'x', '신청자2', 'CONSUMER')")
                .executeUpdate();

        userId1 = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'applicant1@test.com'")
                .getSingleResult()).longValue();
        userId2 = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'applicant2@test.com'")
                .getSingleResult()).longValue();
    }

    // ============================================================
    // 부분 유니크 인덱스 — PENDING 중복 차단
    // ============================================================

    @Test
    @DisplayName("부분 유니크: 동일 userId로 PENDING 2건 INSERT → DataIntegrityViolationException")
    void partial_unique_index_prevents_duplicate_pending() {
        SellerApplication first = SellerApplication.submit(userId1, "상호A", "1234567890", "010-1111-1111");
        repository.saveAndFlush(first);

        SellerApplication second = SellerApplication.submit(userId1, "상호B", "9876543210", "010-2222-2222");

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("부분 유니크: 서로 다른 userId는 각자 PENDING 1건 허용")
    void partial_unique_index_allows_pending_for_different_users() {
        SellerApplication app1 = SellerApplication.submit(userId1, "상호A", "1234567890", "010-1111-1111");
        SellerApplication app2 = SellerApplication.submit(userId2, "상호B", "9876543210", "010-2222-2222");

        repository.saveAndFlush(app1);
        repository.saveAndFlush(app2);

        assertThat(repository.existsByUserIdAndStatus(userId1, SellerApplicationStatus.PENDING)).isTrue();
        assertThat(repository.existsByUserIdAndStatus(userId2, SellerApplicationStatus.PENDING)).isTrue();
    }

    // ============================================================
    // REJECTED 후 재신청 허용
    // ============================================================

    @Test
    @DisplayName("REJECTED 1건 존재 + 새 PENDING INSERT → 성공 (재신청 허용)")
    void rejected_then_new_pending_is_allowed() {
        SellerApplication first = SellerApplication.submit(userId1, "상호A", "1234567890", "010-1111-1111");
        first = repository.saveAndFlush(first);
        first.reject(userId2, "서류 미비"); // userId2가 reviewer (존재하는 users.id)
        repository.saveAndFlush(first);

        SellerApplication second = SellerApplication.submit(userId1, "상호A_재신청", "1234567890", "010-3333-3333");
        repository.saveAndFlush(second);

        assertThat(repository.existsByUserIdAndStatus(userId1, SellerApplicationStatus.PENDING)).isTrue();
    }

    @Test
    @DisplayName("APPROVED 1건 존재 + 새 PENDING INSERT → 성공 (부분 유니크 APPROVED 미대상)")
    void approved_then_new_pending_is_allowed() {
        SellerApplication first = SellerApplication.submit(userId1, "상호A", "1234567890", "010-1111-1111");
        first = repository.saveAndFlush(first);
        first.approve(userId2); // userId2가 reviewer (존재하는 users.id)
        repository.saveAndFlush(first);

        SellerApplication second = SellerApplication.submit(userId1, "상호A_재신청", "1234567890", "010-4444-4444");
        repository.saveAndFlush(second);

        assertThat(repository.existsByUserIdAndStatus(userId1, SellerApplicationStatus.PENDING)).isTrue();
    }

    // ============================================================
    // existsByUserIdAndStatus
    // ============================================================

    @Test
    @DisplayName("existsByUserIdAndStatus — PENDING 없으면 false")
    void exists_returns_false_when_no_pending() {
        assertThat(repository.existsByUserIdAndStatus(userId1, SellerApplicationStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("existsByUserIdAndStatus — PENDING 있으면 true")
    void exists_returns_true_when_pending_exists() {
        repository.saveAndFlush(
                SellerApplication.submit(userId1, "상호", "1234567890", "010-0000-0000"));
        assertThat(repository.existsByUserIdAndStatus(userId1, SellerApplicationStatus.PENDING)).isTrue();
    }

    // ============================================================
    // findFirstByUserIdOrderByCreatedAtDesc
    // ============================================================

    @Test
    @DisplayName("findFirstByUserIdOrderByCreatedAtDesc — 이력 없으면 Optional.empty()")
    void find_first_returns_empty_when_no_history() {
        Optional<SellerApplication> result =
                repository.findFirstByUserIdOrderByCreatedAtDesc(userId1);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findFirstByUserIdOrderByCreatedAtDesc — 신청 있으면 최신 1건 반환")
    void find_first_returns_latest_application() {
        repository.saveAndFlush(
                SellerApplication.submit(userId1, "상호1", "1234567890", "010-0000-0001"));

        Optional<SellerApplication> result =
                repository.findFirstByUserIdOrderByCreatedAtDesc(userId1);
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(userId1);
    }

    // ============================================================
    // search(status, pageable)
    // ============================================================

    @Test
    @DisplayName("search(null) — 전체 조회")
    void search_null_status_returns_all() {
        SellerApplication p = SellerApplication.submit(userId1, "상호", "1234567890", "010-0000-0000");
        p = repository.saveAndFlush(p);
        p.reject(userId2, "사유"); // userId2가 reviewer (존재하는 users.id)
        repository.saveAndFlush(p);

        repository.saveAndFlush(
                SellerApplication.submit(userId2, "상호2", "0987654321", "010-9999-9999"));

        Page<SellerApplication> result = repository.search(null, PageRequest.of(0, 20));
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("search(PENDING) — PENDING만 반환")
    void search_pending_status_returns_only_pending() {
        SellerApplication p = SellerApplication.submit(userId1, "상호", "1234567890", "010-0000-0000");
        p = repository.saveAndFlush(p);
        p.reject(userId2, "사유"); // userId2가 reviewer (존재하는 users.id)
        repository.saveAndFlush(p);

        repository.saveAndFlush(
                SellerApplication.submit(userId2, "상호2", "0987654321", "010-9999-9999"));

        Page<SellerApplication> result =
                repository.search(SellerApplicationStatus.PENDING, PageRequest.of(0, 20));
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getUserId()).isEqualTo(userId2);
    }
}
