package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
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
 * 주문 이행 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>동시 createShipment(같은 주문·같은 미발송 항목): 주문 row 락 직렬화 + UNIQUE로 항목 이중 배정 0,
 *       한쪽만 성공·다른쪽 409</li>
 *   <li>배송 생성 vs 018 취소 동시: 주문 row 락 직렬화, 모순 없음
 *       (배송 생성 먼저 커밋 → 취소 409 / 취소 먼저 커밋 → 배송 생성 409)</li>
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
class OrderFulfillmentConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private OrderCancellation orderCancellation;

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
    // 동시 createShipment — 같은 주문·같은 항목
    // ============================================================

    @Test
    @DisplayName("동시 createShipment(같은 항목): 한쪽만 성공, 항목 이중 배정 0")
    void concurrentCreateShipment_sameOrder_onlyOneSucceeds() throws Exception {
        // given
        long userId = insertUser("conc-fulfill-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertPaidOrder(userId, variantId, "동시성상품", 1, BigDecimal.valueOf(3000));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    orderFulfillmentService.createShipment(orderId, null);
                    successCount.incrementAndGet();
                } catch (OrderFulfillmentConflictException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
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
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);

        // 성공은 정확히 1건
        assertThat(successCount.get()).isEqualTo(1);

        // shipments 정확히 1건
        Integer shipmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, orderId);
        assertThat(shipmentCount).isEqualTo(1);

        // shipment_items 정확히 1건 (이중 배정 없음)
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_items si "
                + "JOIN shipments s ON si.shipment_id=s.id WHERE s.order_id=?",
                Integer.class, orderId);
        assertThat(itemCount).isEqualTo(1);
    }

    // ============================================================
    // 배송 생성 vs 018 취소 동시
    // ============================================================

    @Test
    @DisplayName("배송 생성 vs 018 취소 동시: 주문 row 락 직렬화, 모순 없음")
    void createShipmentVsCancel_serialized_noContradiction() throws Exception {
        // given
        long userId = insertUser("conc-fulfill-2@test.com");
        long variantId = insertVariant(10);
        long orderId = insertPaidOrder(userId, variantId, "동시성취소상품", 1, BigDecimal.valueOf(5000));

        configProductMock(variantId, BigDecimal.valueOf(5000));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: 배송 생성
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.createShipment(orderId, null);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // 스레드 2: 018 취소
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

        // 정확히 1건만 성공
        assertThat(successCount.get()).isEqualTo(1);

        // 모순 없음: 배송+preparing 또는 취소+refunded 중 하나여야 함
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isIn("preparing", "refunded", "cancelled");

        // 모순 상태 불가: preparing이면 shipments 1건, refunded/cancelled이면 shipments 0건
        Integer shipmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, orderId);
        if ("preparing".equals(orderStatus)) {
            assertThat(shipmentCount).isEqualTo(1);
        } else {
            assertThat(shipmentCount).isZero();
        }
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, BigDecimal price) {
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "동시성취소상품", null, List.of(),
                price, true, 100, "ON_SALE", true, null);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariant(int stock) {
        String sku = "FULFILL-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성이행상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertPaidOrder(long userId, long variantId, String productName,
                                 int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-FULFILL-CONC-" + System.nanoTime();
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
}
