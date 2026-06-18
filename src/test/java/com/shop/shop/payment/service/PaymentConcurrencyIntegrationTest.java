package com.shop.shop.payment.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.common.exception.OrderConfirmationConflictException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.OrderCompletedEvent;
import com.shop.shop.payment.dto.PaymentResponse;
import com.shop.shop.payment.repository.PaymentRepository;
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
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 결제 동시성 통합 테스트 (실 PostgreSQL).
 *
 * <p>uq_payments_order_id + saveAndFlush 직렬화 동작을 실 DB에서 검증한다.
 * Mockito로는 unique constraint 충돌을 검증할 수 없으므로 Testcontainers 필수.
 *
 * <p>검증:
 * <ul>
 *   <li>동일 주문에 동시 결제 2건 → 1건 paid, 1건 PaymentInProgressException(409) 또는 OrderConfirmationConflictException</li>
 *   <li>payments 행 1건만 생성 (uq_payments_order_id 보장)</li>
 *   <li>orders 행 status=paid (1번만 전이)</li>
 *   <li>OrderCompletedEvent 1건 발행 (단일 이벤트 보장)</li>
 * </ul>
 *
 * <p>spring.modulith.events.externalization.enabled=false: Kafka 없이 in-memory 이벤트 캡처로 검증.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PaymentConcurrencyIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PaymentConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private PaymentServiceResponse paymentServiceResponse;

    @Autowired
    private PaymentRepository paymentRepository;

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
    void clearEvents() {
        captureListener.clear();
    }

    @Test
    @DisplayName("동일 주문 동시 결제 2건: payments 1건, orders 1번 전이, OrderCompletedEvent 1건")
    void concurrentPay_sameOrder_onlyOneSucceeds() throws Exception {
        // given
        long userId = insertUser("concurrent1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(10000));

        configMocks(userId, variantId, productId, BigDecimal.valueOf(10000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicReference<PaymentResponse> successResponse = new AtomicReference<>();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    PaymentResponse resp = paymentServiceResponse.pay(auth, orderId, null);
                    if ("paid".equals(resp.status())) {
                        successCount.incrementAndGet();
                        successResponse.compareAndSet(null, resp);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (PaymentInProgressException | OrderConfirmationConflictException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    // 기타 직렬화/낙관락 오류도 fail로 처리
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

        // then: 2건 시도
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);

        // payments 행 1건만 생성
        long paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId);
        assertThat(paymentCount).isEqualTo(1);

        // 생성된 payment 는 paid 상태
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("paid");

        // orders paid 전이 1번
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("paid");

        // OrderCompletedEvent 1건 (단일 이벤트 보장)
        assertThat(captureListener.size()).isEqualTo(1);
    }

    // =========================================================
    // 테스트 전용 이벤트 리스너 (OrderCompletedEvent 캡처)
    // =========================================================

    @Component
    static class CaptureListener {
        private final List<OrderCompletedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        public void on(OrderCompletedEvent event) {
            events.add(event);
        }

        public int size() {
            return events.size();
        }

        public void clear() {
            events.clear();
        }
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private void configMocks(long userId, long variantId, long productId, BigDecimal price) {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));

        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "동시성테스트상품", null, List.of(),
                price, true, 100, "ON_SALE", true, null);

        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "CONC-PAY-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('동시성결제테스트상품', '설명', 10000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 10000, ?, true)",
                productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertOrder(long userId, long variantId, BigDecimal amount) {
        String orderNumber = "ORD-CONC-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, ?, ?, ?, ?)",
                userId, orderNumber, amount, amount,
                crypto.encrypt("수령인"), crypto.encrypt("010-1234-5678"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '동시성테스트상품', ?, 1, ?)",
                orderId, variantId, amount, amount);
        return orderId;
    }
}
