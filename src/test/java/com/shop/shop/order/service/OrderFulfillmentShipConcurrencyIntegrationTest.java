package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.ShippingStartedEvent;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.RefundInfo;
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
 * 배송 시작(ship) 동시성 통합 테스트 — Task 020 BLOCKER 2 (정합1 stale-read 보장).
 *
 * <p>검증:
 * <ul>
 *   <li>동일 preparing 배송에 두 스레드 동시 ship → shipments.status=shipping 1건,
 *       ShippingStartedEvent 정확히 1건(stale read로 인한 이벤트 중복 발행 없음).
 *       락 후 fresh 재조회(정합1)로 두 번째 트랜잭션이 멱등 판정해야 함</li>
 *   <li>배송 시작 vs 018 취소 동시 → 주문 row 락으로 직렬화되어 모순(취소된 주문이 배송 시작됨) 없음.
 *       둘 중 하나만 성공하고 상태가 정합적임을 단언</li>
 * </ul>
 *
 * <p>외부 의존 모킹: MemberDirectory, ProductOrderCatalog.
 * Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 * 이벤트 캡처: CaptureListener @TransactionalEventListener(AFTER_COMMIT).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OrderFulfillmentShipConcurrencyIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class OrderFulfillmentShipConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private JdbcTemplate jdbc;

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
                .thenReturn(new MemberContact("conc-ship@example.com", "동시성테스터"));
    }

    // ============================================================
    // 동일 배송 동시 ship — 이벤트 1건만 (정합1 stale-read 방지)
    // ============================================================

    @Test
    @DisplayName("동일 preparing 배송 동시 ship: shipments.status=shipping 1건, event_publication 1건 (stale read 없음)")
    void concurrentShip_samePreparing_onlyOneEventPublished() throws Exception {
        // given
        long userId = insertUser("conc-ship-1@test.com");
        long variantId = insertVariant(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPaidOrder(userId, variantId, "동시성배송상품", 1, BigDecimal.valueOf(5000));

        // createShipment → preparing
        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = getShipmentId(orderId);

        configProductMock(variantId, productId, "동시성배송상품", BigDecimal.valueOf(5000));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 두 스레드가 동시에 같은 shipmentId에 ship 호출
        for (int i = 0; i < 2; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    orderFulfillmentService.ship(shipmentId, "CJ대한통운", "TRK-CONC-00" + threadIdx);
                    successCount.incrementAndGet();
                } catch (OrderFulfillmentConflictException e) {
                    // 두 번째 트랜잭션: 멱등 경로(이미 shipping) → 성공(200)으로 반환됨
                    // 이 catch는 발생하지 않아야 하지만 혹시 예외가 나면 카운트
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

        // then: 2건 처리 완료 (성공 또는 멱등 반환)
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);

        // shipments.status = shipping 1건 (중복 전이 없음)
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("shipping");

        // ShippingStartedEvent 정확히 1건 발행 (stale read로 인한 중복 발행 없음, 정합1)
        // - 첫 번째 트랜잭션: preparing→shipping 전이 + 이벤트 발행
        // - 두 번째 트랜잭션: 락 획득 후 fresh 재조회 → 이미 shipping → 멱등 반환(이벤트 미발행)
        assertThat(captureListener.size()).isEqualTo(1);

        // orders.status = shipping (rollup 1회만)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("shipping");
    }

    // ============================================================
    // 배송 시작 vs 018 취소 동시 — row 락 직렬화, 모순 없음
    // ============================================================

    @Test
    @DisplayName("배송 시작 vs 018 취소 동시: 주문 row 락 직렬화, 모순(취소된 주문 배송 시작) 없음")
    void shipVsCancel_serialized_noContradiction() throws Exception {
        // given
        long userId = insertUser("conc-ship-2@test.com");
        long variantId = insertVariant(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPaidOrder(userId, variantId, "동시성취소배송상품", 1, BigDecimal.valueOf(5000));

        // createShipment → preparing (paid→preparing rollup)
        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = getShipmentId(orderId);

        configProductMock(variantId, productId, "동시성취소배송상품", BigDecimal.valueOf(5000));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: 배송 시작 (preparing→shipping)
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.ship(shipmentId, "CJ대한통운", "TRK-VS-CANCEL");
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
                    // REJECTED(이행단계 취소 불가) 또는 ALREADY_CANCELLED = 실질적 실패
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

        // 정확히 1건만 성공 (배송 시작 OR 취소, 동시 성공 불가)
        assertThat(successCount.get()).isEqualTo(1);

        // 모순 없음: 주문 상태는 shipping 또는 cancelled/refunded 중 하나
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isIn("shipping", "cancelled", "refunded");

        // 상태 정합성 검증:
        // - shipping이면 shipments.status=shipping (배송 시작 성공)
        // - cancelled/refunded이면 shipments.status=preparing (취소 성공, 배송 시작 안 됨)
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);

        if ("shipping".equals(orderStatus)) {
            // 배송 시작 먼저 커밋 → 취소 409
            assertThat(shipmentStatus).isEqualTo("shipping");
        } else {
            // 취소 먼저 커밋 → 배송 시작 409
            assertThat(shipmentStatus).isEqualTo("preparing");
        }
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void configProductMock(long variantId, long productId, String productName, BigDecimal price) {
        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, productName, null, List.of(),
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
        String sku = "SHIP-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('배송동시성상품', '설명', 5000, 'ON_SALE')");
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

    private long insertPaidOrder(long userId, long variantId, String productName,
                                  int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-SHIP-CONC-" + System.nanoTime();
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

    private long getShipmentId(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id ASC LIMIT 1",
                Long.class, orderId);
    }

    // ============================================================
    // 테스트 전용 이벤트 리스너 (이벤트 발행 횟수 측정용)
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<ShippingStartedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(ShippingStartedEvent event) {
            events.add(event);
        }

        public int size() {
            return events.size();
        }

        public void clear() {
            events.clear();
        }
    }
}
