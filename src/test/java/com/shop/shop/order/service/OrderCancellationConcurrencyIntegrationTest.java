package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderCancellationConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.service.PaymentServiceResponse;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 주문 취소 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>PESSIMISTIC_WRITE 락으로 동시 취소·취소-결제 경쟁 상황을 직렬화해야 한다.
 *
 * <p>검증:
 * <ul>
 *   <li>동시 취소 2건: 1건만 CANCELLED, 나머지 ALREADY_CANCELLED 또는 성공 — 중복 복원 없음</li>
 *   <li>취소 vs 결제 직렬화: 락 경합 — 결제 후 취소 OR 취소 후 결제 시도(409) 중 하나만 성공</li>
 *   <li>재고는 정확히 1회만 복원됨 (중복 복원 방지)</li>
 * </ul>
 *
 * <p>동시 취소 직렬화: {@code orderCancellation.cancel()} 안의 {@code findByIdForUpdate}(PESSIMISTIC_WRITE)가
 * 두 트랜잭션을 직렬화 — 선행 트랜잭션이 committed 후 후발이 ALREADY_CANCELLED로 멱등 반환.
 *
 * <p>취소 vs 결제 직렬화: 결제는 {@link PaymentServiceResponse#pay}, 취소는 {@link OrderCancellation#cancel}
 * — 양쪽 모두 {@code findByIdForUpdate}(PESSIMISTIC_WRITE)로 직렬화. 취소가 먼저 커밋하면
 * 후발 결제가 409(OrderConfirmationConflictException)를 던짐.
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
class OrderCancellationConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private PaymentServiceResponse paymentServiceResponse;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // 동시 취소 2건 직렬화
    // ============================================================

    @Test
    @DisplayName("동시 취소 2건: 1건만 CANCELLED(재고 복원 1회), 나머지 ALREADY_CANCELLED 또는 예외")
    void concurrentCancel_sameOrder_onlyOneCancelledAndStockRestoredOnce() throws Exception {
        // given
        long userId = insertUser("conc-cancel1@test.com");
        long variantId = insertVariantWithStock(5); // 초기 재고 5
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 2, BigDecimal.valueOf(3000)); // 2개 주문

        configProductMock(variantId, productId, BigDecimal.valueOf(3000));

        AtomicInteger cancelledCount = new AtomicInteger(0);
        AtomicInteger alreadyOrExceptionCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    OrderCancellationResult result = orderCancellation.cancel(
                            orderId, userId, new RefundInfo(false, 0L, "KRW"));
                    if (result.outcome() == OrderCancellation.Outcome.CANCELLED) {
                        cancelledCount.incrementAndGet();
                    } else {
                        alreadyOrExceptionCount.incrementAndGet();
                    }
                } catch (OrderCancellationConflictException | OrderNotFoundException e) {
                    alreadyOrExceptionCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    alreadyOrExceptionCount.incrementAndGet();
                } catch (Exception e) {
                    // 락 대기 중 직렬화 오류 등 → already/exception으로 처리
                    alreadyOrExceptionCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 2건 처리 완료
        assertThat(cancelledCount.get() + alreadyOrExceptionCount.get()).isEqualTo(2);

        // 취소 성공은 정확히 1건
        assertThat(cancelledCount.get()).isEqualTo(1);

        // orders.status = cancelled (1회만 전이)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("cancelled");

        // 재고 정확히 1회 복원: 5 + 2 = 7
        int stock = getStock(variantId);
        assertThat(stock).isEqualTo(7);
    }

    // ============================================================
    // 취소 vs 결제 직렬화
    // ============================================================

    @Test
    @DisplayName("취소 vs 결제 직렬화: 둘 중 하나만 성공, 재고는 정합적")
    void cancelVsPay_serialized_onlyOneSucceeds() throws Exception {
        // given
        long userId = insertUser("cancel-vs-pay1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, 1, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: 취소 시도
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                OrderCancellationResult result = orderCancellation.cancel(
                        orderId, userId, new RefundInfo(false, 0L, "KRW"));
                if (result.outcome() == OrderCancellation.Outcome.CANCELLED) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // 스레드 2: 결제 시도
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                PaymentResponse resp = paymentServiceResponse.pay(auth, orderId, null);
                if ("paid".equals(resp.status())) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 2건 처리 완료
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);

        // 정확히 1건만 성공
        assertThat(successCount.get()).isEqualTo(1);

        // orders.status = cancelled 또는 paid (둘 중 하나)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isIn("cancelled", "paid", "refunded");

        // 재고 무결성: 취소 시 복원(10+1=11), 결제 시 미복원(10 그대로) — 둘 중 하나
        int stock = getStock(variantId);
        // 취소 성공 시: 10 + 1 = 11, 결제 성공 시: 10 (감소 없음 - inventory 모듈이 별도 처리)
        assertThat(stock).isGreaterThanOrEqualTo(10);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, long productId, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "동시성취소상품", null, List.of(),
                price, true, 100, "ON_SALE", true);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "CANCEL-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성취소상품', '설명', 5000, 'ON_SALE')");
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

    private long insertPendingOrder(long userId, long variantId, int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-CONC-CANCEL-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '동시성취소상품', ?, ?, ?)",
                orderId, variantId, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private int getStock(long variantId) {
        return jdbc.queryForObject(
                "SELECT pv.stock FROM product_variants pv WHERE pv.id=?", Integer.class, variantId);
    }
}
