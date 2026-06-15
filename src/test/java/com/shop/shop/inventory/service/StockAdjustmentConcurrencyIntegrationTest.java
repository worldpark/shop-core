package com.shop.shop.inventory.service;

import com.shop.shop.inventory.repository.StockLedgerRepository;
import com.shop.shop.inventory.spi.InventoryStockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 조정 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>RED→GREEN 검증 절차 (testing-rule 준수):
 * <ol>
 *   <li>RED 확인: {@link InventoryStockPortImpl#adjustStock}의 {@code findByIdForUpdate}를
 *       {@code findById}(비락)로 교체한 변형으로 아래 두 테스트를 실행하면 lost-update가 발생해
 *       {@code concurrentIncrease_pessimisticLock_serializes}는 최종 stock 단언이,
 *       {@code concurrentMixedDelta_pessimisticLock_serializes}는 최종 stock 단언이 깨진다.
 *       (RED 경험 확인 완료 — 비락 변형에서 두 테스트 모두 FAIL)</li>
 *   <li>GREEN: {@code findByIdForUpdate}(PESSIMISTIC_WRITE) 원복 → 두 테스트 모두 GREEN.</li>
 * </ol>
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>{@link #concurrentIncrease_pessimisticLock_serializes}: 동시 증가 5건 → 합산 정합 + 원장 5건</li>
 *   <li>{@link #concurrentMixedDelta_pessimisticLock_serializes}: 동시 증가·감소 혼합 → 최종 stock 정합 +
 *       원장 건수 정합. lost-update가 있으면 최종 stock이 어긋나 단언이 깨진다.</li>
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
class StockAdjustmentConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private InventoryStockPort inventoryStockPort;

    @Autowired
    private StockLedgerRepository stockLedgerRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 시나리오 1: 동시 증가 5건.
     *
     * <p>lost-update 없이 직렬화되면 최종 stock = 초기값 + N·delta.
     * 비락 조회 변형에서는 lost-update → 최종 stock &lt; 기대값 → 단언 FAIL (RED 경험 완료).
     */
    @Test
    @DisplayName("동시 증가 5건: PESSIMISTIC_WRITE 직렬화 → 최종 stock 정합 + 원장 5건")
    void concurrentIncrease_pessimisticLock_serializes() throws Exception {
        // given: 초기 재고 100, 각 스레드가 +1씩 5회 조정
        int initialStock = 100;
        int threadCount = 5;
        int deltaPerThread = 1;
        int expectedFinalStock = initialStock + threadCount * deltaPerThread;

        long variantId = insertVariant(initialStock);
        long actorId = insertUser();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int finalI = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryStockPort.adjustStock(variantId, deltaPerThread, actorId,
                            "동시 증가 조정 #" + finalI);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then: 모든 조정 성공
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // 최종 stock = 초기값 + sum(delta) (잃어버린 갱신 없음)
        Integer finalStock = jdbc.queryForObject(
                "SELECT stock FROM product_variants WHERE id = ?", Integer.class, variantId);
        assertThat(finalStock).isEqualTo(expectedFinalStock);

        // 원장 건수 = threadCount (각 조정이 1건씩 기록)
        var ledgerPage = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(
                variantId, org.springframework.data.domain.PageRequest.of(0, 100));
        assertThat(ledgerPage.getTotalElements()).isEqualTo(threadCount);
    }

    /**
     * 시나리오 2: 동시 증가·감소 혼합 8건 (증가 5, 감소 3).
     *
     * <p>초기 재고를 작게(10) 설정하고 감소폭(-3)을 두어 lost-update가 있으면
     * 최종 stock이 어긋나거나 음수 거부가 부정합해 단언이 깨진다.
     * PESSIMISTIC_WRITE 직렬화 시 최종 stock = 초기 + sum(delta) = 10 + 5·1 + 3·(-1) = 12.
     *
     * <p>비락 변형에서는 lost-update로 최종 stock이 기대값과 달라지거나
     * 원장 건수가 8이 아닌 값으로 단언 FAIL (RED 경험 완료).
     */
    @Test
    @DisplayName("동시 증가·감소 혼합 8건: PESSIMISTIC_WRITE 직렬화 → 최종 stock 정합 + 원장 8건")
    void concurrentMixedDelta_pessimisticLock_serializes() throws Exception {
        // given: 초기 재고 10, 증가 5건(+1), 감소 3건(-1) 동시 실행
        int initialStock = 10;
        int increaseCount = 5;
        int decreaseCount = 3;
        int totalThreadCount = increaseCount + decreaseCount;
        // 직렬화 시 모두 성공 → 최종 stock = 10 + 5·(+1) + 3·(-1) = 12
        int expectedFinalStock = initialStock + increaseCount * 1 + decreaseCount * (-1);

        long variantId = insertVariant(initialStock);
        long actorId = insertUser();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(totalThreadCount);
        List<Future<?>> futures = new ArrayList<>();

        // 증가 스레드 5개
        for (int i = 0; i < increaseCount; i++) {
            int finalI = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryStockPort.adjustStock(variantId, 1, actorId, "혼합 증가 #" + finalI);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // 감소 스레드 3개
        for (int i = 0; i < decreaseCount; i++) {
            int finalI = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryStockPort.adjustStock(variantId, -1, actorId, "혼합 감소 #" + finalI);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then: 모든 조정 성공 (음수 거부 없음 — 직렬화되면 최솟값 stock은 항상 ≥ 0)
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(totalThreadCount);

        // 최종 stock = 직렬화된 순서의 합산 (잃어버린 갱신 없음)
        Integer finalStock = jdbc.queryForObject(
                "SELECT stock FROM product_variants WHERE id = ?", Integer.class, variantId);
        assertThat(finalStock).isEqualTo(expectedFinalStock);

        // 원장 건수 = totalThreadCount (각 조정 1건씩)
        var ledgerPage = stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(
                variantId, org.springframework.data.domain.PageRequest.of(0, 100));
        assertThat(ledgerPage.getTotalElements()).isEqualTo(totalThreadCount);
    }

    private long insertVariant(int stock) {
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                    + "VALUES ('동시성테스트상품', '설명', 1000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);

        String sku = "CONCURRENT-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                    + "VALUES (?, ?, 1000, ?, true)", productId, sku, stock);

        return jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long insertUser() {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) VALUES (?, 'pw', '운영자', 'ADMIN')",
                "admin-concurrent-" + System.nanoTime() + "@test.com");
        return jdbc.queryForObject("SELECT id FROM users ORDER BY id DESC LIMIT 1", Long.class);
    }
}
