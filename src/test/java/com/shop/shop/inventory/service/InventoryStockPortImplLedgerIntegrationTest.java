package com.shop.shop.inventory.service;

import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.inventory.spi.StockChangeReason;
import com.shop.shop.inventory.repository.StockLedgerRepository;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.inventory.spi.InventoryStockPort.StockChangeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InventoryStockPortImpl} 원장 적재 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>decrease → ORDER_DECREASE 원장 1건 기록 (전후 수량 정확)</li>
 *   <li>increase → CANCEL_RESTORE 원장 1건 기록</li>
 *   <li>increase → EXPIRY_RESTORE 원장 1건 기록</li>
 *   <li>increase variant 미존재 skip → 원장 미기록 (핵심)</li>
 *   <li>adjustStock 양수 delta 정상 → ADJUSTMENT 원장 기록</li>
 *   <li>adjustStock 음수 delta 정상 → ADJUSTMENT 원장 기록</li>
 *   <li>adjustStock 음수 재고 결과 → 409 + 원장 미기록</li>
 *   <li>adjustStock variant 미존재 → 404</li>
 *   <li>getLedger: occurred_at DESC 조회</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class InventoryStockPortImplLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private InventoryStockPort inventoryStockPort;

    @Autowired
    private StockLedgerRepository stockLedgerRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ============================================================
    // decrease → ORDER_DECREASE 원장 기록
    // ============================================================

    @Test
    @DisplayName("decrease → ORDER_DECREASE 원장 1건 기록 (delta=-qty, before=10, after=7)")
    @Transactional
    void decrease_recordsLedgerEntry() {
        long variantId = insertVariant(10);

        inventoryStockPort.decrease(variantId, 3, StockChangeContext.system(StockChangeReason.ORDER_DECREASE));

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var entry = page.getContent().get(0);
        assertThat(entry.getDelta()).isEqualTo(-3);
        assertThat(entry.getReason()).isEqualTo(StockChangeReason.ORDER_DECREASE);
        assertThat(entry.getQuantityBefore()).isEqualTo(10);
        assertThat(entry.getQuantityAfter()).isEqualTo(7);
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getMemo()).isNull();
    }

    // ============================================================
    // increase → CANCEL_RESTORE / EXPIRY_RESTORE 원장 기록
    // ============================================================

    @Test
    @DisplayName("increase → CANCEL_RESTORE 원장 1건 기록 (delta=+qty)")
    @Transactional
    void increase_cancelRestore_recordsLedgerEntry() {
        long variantId = insertVariant(5);

        inventoryStockPort.increase(variantId, 3, StockChangeContext.system(StockChangeReason.CANCEL_RESTORE));

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var entry = page.getContent().get(0);
        assertThat(entry.getDelta()).isEqualTo(3);
        assertThat(entry.getReason()).isEqualTo(StockChangeReason.CANCEL_RESTORE);
        assertThat(entry.getQuantityBefore()).isEqualTo(5);
        assertThat(entry.getQuantityAfter()).isEqualTo(8);
    }

    @Test
    @DisplayName("increase → EXPIRY_RESTORE 원장 1건 기록")
    @Transactional
    void increase_expiryRestore_recordsLedgerEntry() {
        long variantId = insertVariant(0);

        inventoryStockPort.increase(variantId, 2, StockChangeContext.system(StockChangeReason.EXPIRY_RESTORE));

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var entry = page.getContent().get(0);
        assertThat(entry.getDelta()).isEqualTo(2);
        assertThat(entry.getReason()).isEqualTo(StockChangeReason.EXPIRY_RESTORE);
    }

    // ============================================================
    // increase variant 미존재 skip → 원장 미기록 (핵심)
    // ============================================================

    @Test
    @DisplayName("increase variant 미존재 → 복원 skip + 원장 미기록 (핵심)")
    @Transactional
    void increase_nonExistentVariant_noLedgerEntry() {
        long nonExistentVariantId = 999_999L;

        // 예외 미발생 — skip + log
        inventoryStockPort.increase(nonExistentVariantId, 1, StockChangeContext.system(StockChangeReason.CANCEL_RESTORE));

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(
                nonExistentVariantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    // ============================================================
    // adjustStock
    // ============================================================

    @Test
    @DisplayName("adjustStock 양수 delta → ADJUSTMENT 원장 기록 (before=10, after=15)")
    @Transactional
    void adjustStock_positiveDelta_recordsAdjustmentLedger() {
        long variantId = insertVariant(10);
        long actorId = insertUser();

        inventoryStockPort.adjustStock(variantId, 5, actorId, "재고 입고");

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var entry = page.getContent().get(0);
        assertThat(entry.getDelta()).isEqualTo(5);
        assertThat(entry.getReason()).isEqualTo(StockChangeReason.ADJUSTMENT);
        assertThat(entry.getQuantityBefore()).isEqualTo(10);
        assertThat(entry.getQuantityAfter()).isEqualTo(15);
        assertThat(entry.getActorId()).isEqualTo(actorId);
        assertThat(entry.getMemo()).isEqualTo("재고 입고");
    }

    @Test
    @DisplayName("adjustStock 음수 delta → ADJUSTMENT 원장 기록 (before=10, after=7)")
    @Transactional
    void adjustStock_negativeDelta_recordsAdjustmentLedger() {
        long variantId = insertVariant(10);
        long actorId = insertUser();

        inventoryStockPort.adjustStock(variantId, -3, actorId, "손상 폐기");

        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        var entry = page.getContent().get(0);
        assertThat(entry.getDelta()).isEqualTo(-3);
        assertThat(entry.getQuantityBefore()).isEqualTo(10);
        assertThat(entry.getQuantityAfter()).isEqualTo(7);
    }

    @Test
    @DisplayName("adjustStock 음수 재고 결과 → InsufficientStockException(409) + 원장 미기록 (롤백)")
    void adjustStock_negativeResult_throwsAndNoLedgerEntry() {
        long variantId = insertVariantNonTransactional(5);
        long actorId = insertUserNonTransactional();

        assertThatThrownBy(() -> inventoryStockPort.adjustStock(variantId, -10, actorId, "과다 감소"))
                .isInstanceOf(InsufficientStockException.class);

        // 롤백 → 원장 미기록
        var page = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("adjustStock variant 미존재 → VariantNotFoundException(404)")
    void adjustStock_nonExistentVariant_throwsVariantNotFoundException() {
        long actorId = insertUserNonTransactional();

        assertThatThrownBy(() -> inventoryStockPort.adjustStock(999_998L, 5, actorId, "조정"))
                .isInstanceOf(VariantNotFoundException.class);
    }

    // ============================================================
    // getLedger
    // ============================================================

    @Test
    @DisplayName("getLedger: occurred_at DESC 정렬 + StockLedgerView 매핑 정확")
    @Transactional
    void getLedger_returnsViewsOrderedByOccurredAtDesc() {
        long variantId = insertVariant(20);

        inventoryStockPort.decrease(variantId, 3, StockChangeContext.system(StockChangeReason.ORDER_DECREASE));
        inventoryStockPort.increase(variantId, 1, StockChangeContext.system(StockChangeReason.CANCEL_RESTORE));

        Page<InventoryStockPort.StockLedgerView> page =
                inventoryStockPort.getLedger(variantId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        // 최신(CANCEL_RESTORE)이 첫 번째
        assertThat(page.getContent().get(0).reason()).isEqualTo(StockChangeReason.CANCEL_RESTORE);
        assertThat(page.getContent().get(1).reason()).isEqualTo(StockChangeReason.ORDER_DECREASE);
    }

    // ============================================================
    // 헬퍼 (트랜잭션 내부 — @Transactional 붙은 테스트용)
    // ============================================================

    private long insertVariant(int stock) {
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                    + "VALUES ('테스트상품', '설명', 1000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);

        String sku = "SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                    + "VALUES (?, ?, 1000, ?, true)", productId, sku, stock);

        return jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long insertUser() {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) VALUES (?, 'pw', '운영자', 'ADMIN')",
                "admin-" + System.nanoTime() + "@test.com");
        return jdbc.queryForObject("SELECT id FROM users ORDER BY id DESC LIMIT 1", Long.class);
    }

    // @Transactional이 없는 테스트에서 별도 커밋 없이 데이터 삽입 (기존 트랜잭션이 없으므로 직접 실행)
    private long insertVariantNonTransactional(int stock) {
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                    + "VALUES ('테스트상품NTX', '설명', 1000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);

        String sku = "SKU-NTX-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                    + "VALUES (?, ?, 1000, ?, true)", productId, sku, stock);

        return jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long insertUserNonTransactional() {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) VALUES (?, 'pw', '운영자NTX', 'ADMIN')",
                "admin-ntx-" + System.nanoTime() + "@test.com");
        return jdbc.queryForObject("SELECT id FROM users ORDER BY id DESC LIMIT 1", Long.class);
    }
}
