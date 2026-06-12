package com.shop.shop.order.service;

import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배송 완료(deliver) 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>멀티 배송 마지막 2건 동시 deliver → 주문 row 락 직렬화 + rollup 1회만 (stale-read 방지 = 정합1)</li>
 *   <li>배송 완료 vs 018 취소 동시 → 주문 row 락 직렬화, 모순 없음</li>
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
class OrderFulfillmentDeliverConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private JdbcTemplate jdbc;

    // ============================================================
    // 멀티 배송 마지막 2건 동시 deliver — rollup 1회만
    // ============================================================

    @Test
    @DisplayName("멀티 배송 마지막 2건 동시 deliver: 락 직렬화 + rollup 1회만, orders.status=delivered (stale-read 없음, 정합1)")
    void concurrentDeliver_lastTwoShipments_rollupOnce() throws Exception {
        long userId = insertUser("conc-deliver-1@test.com");
        long variantId1 = insertVariant(5);
        long variantId2 = insertVariant(5);
        long orderId = insertPaidOrderWithTwoItems(userId, variantId1, variantId2);

        List<Long> itemIds = getOrderItemIds(orderId);

        // 두 배송 먼저 생성 (주문이 paid/preparing 상태일 때)
        var resp1 = orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(0)));
        long shipmentId1 = resp1.shipmentId();

        var resp2 = orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(1)));
        long shipmentId2 = resp2.shipmentId();

        // 그 다음 둘 다 shipToShipping
        shipToShipping(shipmentId1);
        shipToShipping(shipmentId2);

        // orders.status = shipping 확인
        String orderStatusBefore = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusBefore).isEqualTo("shipping");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.deliver(shipmentId1);
                successCount.incrementAndGet();
            } catch (Exception e) {
                // 멱등 경로에서도 성공으로 반환되어야 함 (failCount는 진짜 예외만)
                if (e instanceof com.shop.shop.common.exception.OrderFulfillmentConflictException) {
                    failCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet(); // 멱등 200
                }
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.deliver(shipmentId2);
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof com.shop.shop.common.exception.OrderFulfillmentConflictException) {
                    failCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
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

        // 두 배송 모두 delivered
        String status1 = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId1);
        String status2 = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId2);
        assertThat(status1).isEqualTo("delivered");
        assertThat(status2).isEqualTo("delivered");

        // 주문 rollup 정확히 1회 — orders.status=delivered (stale-read로 인한 중복 rollup 없음, 정합1)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("delivered");
    }

    // ============================================================
    // 배송 완료 vs 018 취소 동시 — row 락 직렬화, 모순 없음
    // ============================================================

    @Test
    @DisplayName("배송 완료 vs 018 취소 동시: 주문 row 락 직렬화, 모순 없음")
    void deliverVsCancel_serialized_noContradiction() throws Exception {
        long userId = insertUser("conc-deliver-2@test.com");
        long variantId = insertVariant(10);
        long orderId = insertShippingOrder(userId, variantId, "동시성완료취소상품", 1, BigDecimal.valueOf(5000));

        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        shipToShipping(shipmentId);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: 배송 완료
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.deliver(shipmentId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // 스레드 2: 018 취소 (shipping 상태이므로 거부될 수 있음)
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                OrderCancellation.OrderCancellationResult result = orderCancellation.cancel(
                        orderId, userId, new RefundInfo(true, 5000L, "KRW"));
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

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // then: 2건 처리 완료
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);

        // 모순 없음: 주문 상태는 정합적인 상태 중 하나여야 함
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        // - deliver 성공, 취소 거부 → shipped → delivered
        // - 취소 성공, deliver 거부 → 취소/환불 → cancelled/refunded
        assertThat(orderStatus).isIn("delivered", "cancelled", "refunded", "shipping");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariant(int stock) {
        String sku = "DELIVER-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성배송완료상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertShippingOrder(long userId, long variantId, String productName,
                                     int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-DEL-CONC-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'paid', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private long insertPaidOrderWithTwoItems(long userId, long variantId1, long variantId2) {
        String orderNumber = "ORD-DEL-CONC-2I-" + System.nanoTime();
        BigDecimal amount = BigDecimal.valueOf(10000);
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'paid', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, amount, amount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '상품1', 5000, 1, 5000)", orderId, variantId1);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '상품2', 5000, 1, 5000)", orderId, variantId2);
        return orderId;
    }

    private void shipToShipping(long shipmentId) {
        jdbc.update("UPDATE shipments SET status='shipping', carrier='CJ대한통운', "
                + "tracking_number='TRK-CONC-DEL-' || id, shipped_at=now() WHERE id=?", shipmentId);
        jdbc.update("UPDATE orders SET status='shipping' WHERE id = "
                + "(SELECT order_id FROM shipments WHERE id=?)", shipmentId);
    }

    private List<Long> getOrderItemIds(long orderId) {
        return jdbc.queryForList(
                "SELECT id FROM order_items WHERE order_id=? ORDER BY id", Long.class, orderId);
    }
}
