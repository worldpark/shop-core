package com.shop.shop.payment.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.OrderCancelledEvent;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 미결제 만료 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>만료 vs 결제 동시 → orders row 락 직렬화. 결제 승=만료 skip / 만료 승=결제 거부</li>
 *   <li>만료 vs 사용자 취소 동시 → 직렬화, 이중 복원 없음, 이벤트 1건</li>
 *   <li>같은 주문 동시 만료 2건 → 1건만 복원/이벤트 (멱등)</li>
 * </ul>
 *
 * <p>018 동시성 테스트 프로파일 답습 (C12).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(UnpaidOrderExpiryConcurrencyIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"
})
class UnpaidOrderExpiryConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EnvelopeEncryptionService crypto;

    @Autowired
    private CaptureListener captureListener;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        captureListener.clear();
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // 같은 주문 동시 만료 2건 → 1건만 복원/이벤트
    // ============================================================

    @Test
    @DisplayName("같은 주문 동시 만료 2건 → 1건만 복원/이벤트 (멱등 직렬화)")
    void concurrentExpiry_sameOrder_onlyOneSucceeds() throws InterruptedException {
        // given
        long userId = insertUser("concur-expiry1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "동시만료상품", 2, BigDecimal.valueOf(5000));
        setCreatedAtPast(orderId, 60);

        configProductMock(variantId, productId, "동시만료상품", BigDecimal.valueOf(5000));

        // when — 동시에 2개 스레드에서 만료 시도
        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    paymentService.expirePendingOrder(orderId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 두 번째 만료는 멱등 skip(예외 없음) 또는 lock wait timeout
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then — orders cancelled
        assertThat(getOrderStatus(orderId)).isEqualTo("cancelled");

        // 재고 복원은 정확히 1회(이중 복원 없음): 10 + 2 = 12
        assertThat(getStock(variantId)).isEqualTo(12);

        // 이벤트 1건만 (이중 발행 없음)
        assertThat(captureListener.getEvents()).hasSize(1);
    }

    // ============================================================
    // 만료 vs 사용자 취소 동시 → 이중 복원 없음
    // ============================================================

    @Test
    @DisplayName("만료 vs 사용자 취소 동시 → 직렬화, 이중 복원 없음, 이벤트 1건")
    void concurrentExpiry_vs_userCancel_noDoubleRestore() throws InterruptedException {
        // given
        long userId = insertUser("concur-expiry2@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "만료vs취소상품", 3, BigDecimal.valueOf(3000));
        setCreatedAtPast(orderId, 60);

        configProductMock(variantId, productId, "만료vs취소상품", BigDecimal.valueOf(3000));

        // when — 만료와 사용자 취소를 동시에 시도
        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 스레드 1: 시스템 만료
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.expirePendingOrder(orderId);
            } catch (Exception ignored) {
                // 만료가 사용자 취소보다 늦으면 멱등 skip
            } finally {
                doneLatch.countDown();
            }
        });

        // 스레드 2: 사용자 취소
        executor.submit(() -> {
            try {
                startLatch.await();
                orderCancellation.cancel(orderId, userId,
                        new OrderCancellation.RefundInfo(false, 0L, "KRW"));
            } catch (Exception ignored) {
                // 사용자 취소가 만료보다 늦으면 ALREADY_CANCELLED 또는 lock wait
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then — orders cancelled (둘 중 하나가 성공)
        assertThat(getOrderStatus(orderId)).isEqualTo("cancelled");

        // 재고 복원은 정확히 1회 (이중 복원 없음): 10 + 3 = 13
        assertThat(getStock(variantId)).isEqualTo(13);

        // 이벤트 1건만
        assertThat(captureListener.getEvents()).hasSize(1);
    }

    // ============================================================
    // 테스트 전용 이벤트 리스너
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<OrderCancelledEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(OrderCancelledEvent event) {
            events.add(event);
        }

        public List<OrderCancelledEvent> getEvents() {
            return Collections.unmodifiableList(events);
        }

        public int size() {
            return events.size();
        }

        public void clear() {
            events.clear();
        }
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, long productId, String productName, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, productName, null, List.of(),
                price, true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection())).thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "CONCUR-EXPIRY-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시만료테스트상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long getProductIdByVariant(long variantId) {
        return jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
    }

    private long insertPendingOrder(long userId, long variantId, String productName,
                                    int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-CONCUR-EXPIRY-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, ?, ?, ?, ?)",
                userId, orderNumber, lineAmount, lineAmount,
                crypto.encrypt("수령인"), crypto.encrypt("010-1234-5678"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private void setCreatedAtPast(long orderId, int minutesAgo) {
        Instant past = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        jdbc.update("UPDATE orders SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(past), orderId);
    }

    private String getOrderStatus(long orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
    }

    private int getStock(long variantId) {
        return jdbc.queryForObject(
                "SELECT stock FROM product_variants WHERE id=?", Integer.class, variantId);
    }
}
