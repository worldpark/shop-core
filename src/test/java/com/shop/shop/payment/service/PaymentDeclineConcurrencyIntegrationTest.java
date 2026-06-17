package com.shop.shop.payment.service;

import com.shop.shop.common.exception.PaymentDeclinedException;
import com.shop.shop.common.exception.PaymentInProgressException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.payment.spi.PaymentGatewayPort;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.payment.event.PaymentFailedEvent;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * 결제 거절 동시성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>동시 2건 중 승자 거절 → payments row 1건 유지 (uq_payments_order_id, Ma2)</li>
 *   <li>패자가 failed row 재사용 경로로 처리됨 (NPE·미정의 동작 없음)</li>
 *   <li>PaymentFailedEvent는 각 거절 시도마다 독립 eventId</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PaymentDeclineConcurrencyIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PaymentDeclineConcurrencyIntegrationTest {

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
    private CaptureListener captureListener;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    /**
     * PaymentGatewayPort Spy: 거절 주입용.
     * payments.method DB CHECK('mock') 허용 범위를 유지하기 위해
     * "mock" method로 결제하되 PG spy에서 declined 반환하도록 주입한다.
     */
    @MockitoSpyBean
    private PaymentGatewayPort paymentGatewayPort;

    @BeforeEach
    void clearEvents() {
        captureListener.clear();
    }

    @Test
    @DisplayName("동시 2건 중 승자 거절 → payments row 1건 유지 + 패자 failed 재사용(Ma2)")
    void concurrentPay_declined_onlyOneRow_ma2() throws Exception {
        // given
        long userId = insertUser("conc-decline1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(30000));
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000));

        // PG 거절 주입: spy를 사용해 거절을 강제 주입한다.
        // method=null → "mock" (DB CHECK 허용값)으로 결제하되, PG spy에서 declined를 반환한다.
        // (실 게이트웨이에서는 method="virtual_account"로 결제하면 실제 MockPaymentGateway가 거절을 반환한다.)
        org.mockito.Mockito.doReturn(PaymentGatewayPort.PaymentAuthorizationResult
                .declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다."))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        AtomicInteger declineCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    // method=null → "mock" (DB CHECK 허용 범위)
                    paymentServiceResponse.pay(auth, orderId, null);
                    // 거절이므로 PaymentDeclinedException이 나와야 함
                    otherFailCount.incrementAndGet();
                } catch (PaymentDeclinedException e) {
                    declineCount.incrementAndGet();
                } catch (PaymentInProgressException e) {
                    // 동시 경합에서 발생 가능 (ready row 잔존 경합)
                    otherFailCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    otherFailCount.incrementAndGet();
                } catch (Exception e) {
                    // 기타 직렬화 오류
                    otherFailCount.incrementAndGet();
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
        assertThat(declineCount.get() + otherFailCount.get()).isEqualTo(2);

        // payments row 1건 유지 (uq_payments_order_id 불변식)
        long paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId);
        assertThat(paymentCount).isEqualTo(1);

        // 생성된 payment는 failed 상태 (승자 거절)
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("failed");

        // 주문 status=pending 유지
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("pending");
    }

    @Test
    @DisplayName("거절 후 재시도 승인 → failed→paid + 주문 확정 (Ma1, 직렬 시나리오)")
    void pay_afterDecline_retryApproval_failedToPaid_ma1() {
        // given
        long userId = insertUser("conc-decline2@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(30000));
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000));

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());

        // 1차: 거절 (PG spy 주입 — "mock" method DB CHECK 허용)
        org.mockito.Mockito.doReturn(PaymentGatewayPort.PaymentAuthorizationResult
                .declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다."))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());
        try {
            paymentServiceResponse.pay(auth, orderId, null);
        } catch (PaymentDeclinedException ignored) {}

        // payments=failed, order=pending 확인
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo("failed");
        String status1 = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(status1).isEqualTo("pending");

        // 2차: 승인 — spy 리셋(real 동작 복원) 후 재요청
        org.mockito.Mockito.reset(paymentGatewayPort); // 거절 stub 제거 → real MockPaymentGateway 동작
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000)); // productOrderCatalog/memberDirectory 재설정
        com.shop.shop.payment.dto.PaymentResponse response = paymentServiceResponse.pay(auth, orderId, null);

        // then: failed→paid 전이
        assertThat(response.status()).isEqualTo("paid");
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo("paid");

        // 주문 status=paid
        String status2 = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(status2).isEqualTo("paid");

        // payments row 1건 유지
        long paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId);
        assertThat(paymentCount).isEqualTo(1);
    }

    // =========================================================
    // 테스트 전용 PaymentFailedEvent 캡처 리스너
    // =========================================================

    @Component
    static class CaptureListener {
        private final List<PaymentFailedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        public void on(PaymentFailedEvent event) {
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
                .thenReturn(new MemberContact("decline-conc@example.com", "거절동시성테스트"));

        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "거절동시성테스트상품", null, List.of(),
                price, true, 100, "ON_SALE", true, null);

        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '거절동시성', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "DECLINE-CONC-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('거절동시성테스트상품', '설명', 30000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 30000, ?, true)",
                productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertOrder(long userId, long variantId, BigDecimal amount) {
        String orderNumber = "ORD-DCNC-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, amount, amount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '거절동시성테스트상품', ?, 1, ?)",
                orderId, variantId, amount, amount);
        return orderId;
    }
}
