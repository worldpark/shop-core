package com.shop.shop.inventory.repository;

import com.shop.shop.inventory.spi.StockChangeReason;
import com.shop.shop.inventory.domain.StockLedgerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StockLedgerRepository} 통합 테스트 (실 PostgreSQL).
 *
 * <p>검증:
 * <ul>
 *   <li>variant_id FK 제약 충족</li>
 *   <li>occurred_at DESC → id DESC 정렬 (최신순 + 동시각 안정)</li>
 *   <li>페이지네이션 (size=2 기준)</li>
 *   <li>actor_id NULL 저장 (시스템 변동)</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class StockLedgerRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private StockLedgerRepository stockLedgerRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("원장 저장 및 조회: variant_id FK, actor_id NULL 허용")
    void save_systemVariantEntry_savedWithNullActorId() {
        long variantId = insertVariant(10);

        StockLedgerEntry entry = StockLedgerEntry.of(
                variantId, -3, StockChangeReason.ORDER_DECREASE,
                10, 7, null, null, Instant.now()
        );
        stockLedgerRepository.save(entry);
        em.flush();
        em.clear();

        Page<StockLedgerEntry> result = stockLedgerRepository
                .findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        StockLedgerEntry found = result.getContent().get(0);
        assertThat(found.getVariantId()).isEqualTo(variantId);
        assertThat(found.getDelta()).isEqualTo(-3);
        assertThat(found.getReason()).isEqualTo(StockChangeReason.ORDER_DECREASE);
        assertThat(found.getQuantityBefore()).isEqualTo(10);
        assertThat(found.getQuantityAfter()).isEqualTo(7);
        assertThat(found.getActorId()).isNull();
        assertThat(found.getMemo()).isNull();
    }

    @Test
    @DisplayName("원장 조회: occurred_at DESC 정렬 — 나중 항목이 먼저")
    void findByVariantId_orderedByOccurredAtDesc() throws InterruptedException {
        long variantId = insertVariant(20);

        Instant earlier = Instant.now().minusSeconds(10);
        Instant later = Instant.now();

        StockLedgerEntry entry1 = StockLedgerEntry.of(
                variantId, -2, StockChangeReason.ORDER_DECREASE, 20, 18, null, null, earlier);
        StockLedgerEntry entry2 = StockLedgerEntry.of(
                variantId, 2, StockChangeReason.CANCEL_RESTORE, 18, 20, null, null, later);

        stockLedgerRepository.save(entry1);
        stockLedgerRepository.save(entry2);
        em.flush();
        em.clear();

        Page<StockLedgerEntry> result = stockLedgerRepository
                .findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getReason()).isEqualTo(StockChangeReason.CANCEL_RESTORE);
        assertThat(result.getContent().get(1).getReason()).isEqualTo(StockChangeReason.ORDER_DECREASE);
    }

    @Test
    @DisplayName("페이지네이션: size=2로 제한, totalElements는 전체")
    void findByVariantId_pagination_respectsSize() {
        long variantId = insertVariant(30);

        for (int i = 0; i < 5; i++) {
            StockLedgerEntry entry = StockLedgerEntry.of(
                    variantId, -1, StockChangeReason.ORDER_DECREASE,
                    30 - i, 29 - i, null, null,
                    Instant.now().minusSeconds(5 - i)
            );
            stockLedgerRepository.save(entry);
        }
        em.flush();
        em.clear();

        Page<StockLedgerEntry> page0 = stockLedgerRepository
                .findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 2));

        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("actor_id와 memo 함께 저장 (운영자 조정)")
    void save_operatorAdjustment_actorIdAndMemoSaved() {
        long variantId = insertVariant(50);
        long actorId = insertUser();

        StockLedgerEntry entry = StockLedgerEntry.of(
                variantId, 5, StockChangeReason.ADJUSTMENT,
                50, 55, actorId, "손실 보충", Instant.now()
        );
        stockLedgerRepository.save(entry);
        em.flush();
        em.clear();

        Page<StockLedgerEntry> result = stockLedgerRepository
                .findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        StockLedgerEntry found = result.getContent().get(0);
        assertThat(found.getActorId()).isEqualTo(actorId);
        assertThat(found.getMemo()).isEqualTo("손실 보충");
        assertThat(found.getReason()).isEqualTo(StockChangeReason.ADJUSTMENT);
    }

    private long insertVariant(int stock) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO products (name, description, base_price, status) "
                        + "VALUES ('테스트상품', '설명', 1000, 'ON_SALE')")
                .executeUpdate();
        long productId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM products ORDER BY id DESC LIMIT 1")
                .getSingleResult()).longValue();

        em.getEntityManager().createNativeQuery(
                        "INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                        + "VALUES (?1, ?2, 1000, ?3, true)")
                .setParameter(1, productId)
                .setParameter(2, "SKU-" + System.nanoTime())
                .setParameter(3, stock)
                .executeUpdate();

        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM product_variants ORDER BY id DESC LIMIT 1")
                .getSingleResult()).longValue();
    }

    private long insertUser() {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES (?1, 'pw', '테스트운영자', 'ADMIN')")
                .setParameter(1, "admin-" + System.nanoTime() + "@test.com")
                .executeUpdate();

        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users ORDER BY id DESC LIMIT 1")
                .getSingleResult()).longValue();
    }
}
