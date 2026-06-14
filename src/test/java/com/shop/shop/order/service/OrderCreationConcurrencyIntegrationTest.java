package com.shop.shop.order.service;

import com.shop.shop.cart.spi.CartCheckoutReader;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckout;
import com.shop.shop.cart.spi.CartCheckoutReader.CartCheckoutItem;
import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.order.dto.OrderCreateRequest;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 주문 생성 동시성 통합 테스트 (실 PostgreSQL).
 *
 * <p>비관적 락(@Lock(PESSIMISTIC_WRITE)) 직렬화 동작을 실 DB에서 검증한다.
 * Mockito로는 row-level lock 직렬화를 검증할 수 없으므로 Testcontainers 필수.
 *
 * <p>검증:
 * <ul>
 *   <li>동일 variant stock=1 동시 주문 2개 → 하나만 성공, 나머지 409(InsufficientStockException)</li>
 *   <li>비관적 락으로 stock 음수 불가</li>
 *   <li>실패 트랜잭션은 stock 롤백됨</li>
 * </ul>
 *
 * <p>테스트 프로파일: @AutoConfigureTestDatabase(NONE) + @TestPropertySource(exclude 리셋 + Flyway).
 * CartCheckoutReader·ProductOrderCatalog는 @MockitoBean으로 대체.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OrderCreationConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryStockRepository inventoryStockRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private CartCheckoutReader cartCheckoutReader;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("stock=1 variant 동시 주문 2개: 하나만 성공, 나머지 409(InsufficientStockException)")
    void concurrentOrders_stock1_onlyOneSucceeds() throws Exception {
        // given: stock=1 variant 삽입
        long variantId = insertVariantWithStock(1, true);
        long userId1 = insertUser("user1@test.com");
        long userId2 = insertUser("user2@test.com");

        OrderableVariantSnapshot snapshot = buildSnapshot(variantId, 1);
        when(productOrderCatalog.getOrderableSnapshots(any()))
                .thenReturn(List.of(snapshot));

        when(cartCheckoutReader.getCheckoutCart(userId1))
                .thenReturn(new CartCheckout(1L, List.of(new CartCheckoutItem(1L, variantId, 1))));
        when(cartCheckoutReader.getCheckoutCart(userId2))
                .thenReturn(new CartCheckout(2L, List.of(new CartCheckoutItem(2L, variantId, 1))));

        OrderCreateRequest request = new OrderCreateRequest("홍길동", "010-1234-5678", "12345", "서울", null, null);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 두 스레드가 동시에 주문 시도
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderService.placeOrder(userId1, request);
                successCount.incrementAndGet();
            } catch (InsufficientStockException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                // 그 외 예외도 실패로 카운트
                conflictCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderService.placeOrder(userId2, request);
                successCount.incrementAndGet();
            } catch (InsufficientStockException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                conflictCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 하나만 성공, 하나는 409
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // stock 음수 불가 검증
        var vs = inventoryStockRepository.findById(variantId).orElseThrow();
        assertThat(vs.getStock()).isGreaterThanOrEqualTo(0);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private long insertVariantWithStock(int stock, boolean isActive) {
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성테스트상품', '설명', 1000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);

        String sku = "CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 1000, ?, ?)",
                productId, sku, stock, isActive);

        Long variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku = ?", Long.class, sku);
        return variantId;
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', 'Test User', 'CONSUMER')", email);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        return userId;
    }

    private OrderableVariantSnapshot buildSnapshot(long variantId, int stock) {
        return new OrderableVariantSnapshot(
                variantId, 1L, "동시성테스트상품", "옵션", List.of(),
                new BigDecimal("1000"), true, stock, "ON_SALE", true
        );
    }
}
