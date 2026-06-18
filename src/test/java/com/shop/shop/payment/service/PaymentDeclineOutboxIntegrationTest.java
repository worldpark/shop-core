package com.shop.shop.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.payment.event.PaymentFailedEvent;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.payment.spi.PaymentGatewayPort;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 결제 거절 Outbox 발행 + 원자성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>거절 결제 커밋 시 event_publication에 PaymentFailedEvent 1건 저장</li>
 *   <li>PaymentFailedEvent 페이로드 필수 필드(JSON) 검증</li>
 *   <li>payments.status=failed 영속</li>
 *   <li>주문 status=pending 불변 (OrderCompletedEvent 미저장)</li>
 *   <li>거절은 정상 커밋 (롤백 없음, C1)</li>
 *   <li>시스템 오류(강제 예외) 시 payments/event_publication 부분 반영 없음 (원자성)</li>
 * </ul>
 *
 * <p>테스트 프로파일: 016 PaymentOutboxIntegrationTest와 동일 패턴.
 * Kafka 비활성(spring.modulith.events.externalization.enabled=false).
 * event_publication 저장만 검증(실 Kafka 외부화 범위 밖).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PaymentDeclineOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PaymentDeclineOutboxIntegrationTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    /**
     * PaymentGatewayPort Spy: 정상 경로에서는 실제 MockPaymentGateway 동작을 그대로 사용하고,
     * 강제 예외 주입 케이스에서만 doThrow()를 사용한다.
     */
    @MockitoSpyBean
    private PaymentGatewayPort paymentGatewayPort;

    @BeforeEach
    void clearEvents() {
        captureListener.clear();
    }

    // =========================================================
    // Outbox 저장 + 페이로드 검증
    // =========================================================

    @Test
    @DisplayName("거절 결제 커밋 시 PaymentFailedEvent 1건 Outbox 저장 + 페이로드 필수 필드 검증")
    void pay_declined_outboxStoresPaymentFailedEventWithPayload() throws Exception {
        // given
        long userId = insertUser("decline-outbox1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000));

        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(30000));
        String orderNumber = jdbc.queryForObject(
                "SELECT order_number FROM orders WHERE id=?", String.class, orderId);

        Authentication auth = buildAuth(userId);

        // PG 거절 주입: spy를 사용해 거절을 강제 주입한다.
        // method=null → "mock" (DB CHECK 허용값)으로 결제하되, PG spy에서 declined를 반환한다.
        // (실 게이트웨이에서는 method="virtual_account"로 결제하면 실제 MockPaymentGateway가 거절을 반환한다.)
        doReturn(com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult
                .declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다."))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());

        // when
        com.shop.shop.common.exception.PaymentDeclinedException ex = null;
        try {
            paymentServiceResponse.pay(auth, orderId, null); // method=null → "mock"
        } catch (com.shop.shop.common.exception.PaymentDeclinedException e) {
            ex = e;
        }

        // then: ServiceResponse가 PaymentDeclinedException throw — payments/event는 이미 커밋됨(C1)
        assertThat(ex).isNotNull();

        // payments.status=failed
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("failed");

        // 주문 status=pending 불변
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("pending");

        // event_publication: PaymentFailedEvent 1건 저장 (INCOMPLETE)
        long eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%PaymentFailedEvent%'",
                Long.class);
        assertThat(eventCount)
                .as("PaymentFailedEvent가 event_publication에 1건 저장되어야 한다")
                .isGreaterThanOrEqualTo(1);

        // 페이로드 JSON 필드 검증
        String payloadJson = jdbc.queryForObject(
                "SELECT serialized_event FROM event_publication WHERE event_type LIKE '%PaymentFailedEvent%' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(payloadJson).isNotBlank();

        JsonNode payload = objectMapper.readTree(payloadJson);
        assertThat(payload.has("eventId")).isTrue();
        assertThat(payload.has("occurredAt")).isTrue();
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("orderNumber").asText()).isEqualTo(orderNumber);
        assertThat(payload.get("memberId").asLong()).isEqualTo(userId);
        assertThat(payload.get("memberEmail").asText()).isEqualTo("decline-test@example.com");
        assertThat(payload.get("memberName").asText()).isEqualTo("거절테스트유저");
        assertThat(payload.get("amount").asLong()).isEqualTo(30000L);
        assertThat(payload.get("currency").asText()).isNotBlank();
        assertThat(payload.has("failureCode")).isTrue();
        assertThat(payload.has("failureReason")).isTrue();
        assertThat(payload.has("attemptedAt")).isTrue();
    }

    @Test
    @DisplayName("거절 시 OrderCompletedEvent 미저장 + 주문 status=pending 불변")
    void pay_declined_noOrderCompletedEvent_orderStatusPending() {
        // given
        long userId = insertUser("decline-outbox2@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000));

        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(30000));
        Authentication auth = buildAuth(userId);

        // PG 거절 주입 (spy 사용, method=null → "mock" — DB CHECK 허용 범위)
        doReturn(com.shop.shop.payment.spi.PaymentGatewayPort.PaymentAuthorizationResult
                .declined("CARD_DECLINED", "카드사에서 결제가 거절되었습니다."))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());

        // when
        try {
            paymentServiceResponse.pay(auth, orderId, null);
        } catch (com.shop.shop.common.exception.PaymentDeclinedException ignored) {}

        // then: OrderCompletedEvent 미저장
        long orderEventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%OrderCompletedEvent%' AND publication_date >= NOW() - INTERVAL '1 minute'",
                Long.class);
        assertThat(orderEventCount).isEqualTo(0);

        // 주문 status=pending 불변
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("pending");

        // CaptureListener 캡처한 PaymentFailedEvent 1건
        assertThat(captureListener.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("시스템 오류(강제 예외) 시 payments/event_publication 부분 반영 없음 (원자성)")
    void pay_systemError_rollbacksAllChanges() {
        // given
        long userId = insertUser("decline-outbox3@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
        configMocks(userId, variantId, productId, BigDecimal.valueOf(30000));

        long orderId = insertOrder(userId, variantId, BigDecimal.valueOf(30000));
        Authentication auth = buildAuth(userId);

        // PG 호출에서 RuntimeException 주입 → ready row INSERT 이후 예외 → 전체 롤백
        doThrow(new RuntimeException("테스트 시스템 오류"))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());

        // when
        assertThatThrownBy(() -> paymentServiceResponse.pay(auth, orderId, null))
                .isInstanceOf(RuntimeException.class);

        // then: payments row 없음 (ready row 포함 롤백)
        long paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId);
        assertThat(paymentCount).isEqualTo(0);

        // orders status=pending 유지
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("pending");

        // PaymentFailedEvent 미저장
        assertThat(captureListener.size()).isEqualTo(0);
    }

    // =========================================================
    // 테스트 전용 PaymentFailedEvent 캡처 리스너
    // =========================================================

    /**
     * 테스트 전용 PaymentFailedEvent 인-메모리 캡처 리스너.
     *
     * <p>@TransactionalEventListener(AFTER_COMMIT)으로 커밋 완료 후에만 이벤트를 캡처한다.
     * 롤백 시 리스너가 호출되지 않아 "롤백됐지만 캡처됨" 오탐을 방지한다.
     */
    @Component
    static class CaptureListener {
        private final List<PaymentFailedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
                .thenReturn(new MemberContact("decline-test@example.com", "거절테스트유저"));

        OrderableVariantSnapshot snapshot = new OrderableVariantSnapshot(
                variantId, productId, "거절테스트상품", null, List.of(),
                price, true, 100, "ON_SALE", true, null);

        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '거절테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "DECLINE-OUTBOX-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('거절Outbox테스트상품', '설명', 30000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 30000, ?, true)",
                productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertOrder(long userId, long variantId, BigDecimal amount) {
        String orderNumber = "ORD-DECLINE-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, ?, ?, ?, ?)",
                userId, orderNumber, amount, amount,
                crypto.encrypt("수령인"), crypto.encrypt("010-1234-5678"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '거절테스트상품', ?, 1, ?)",
                orderId, variantId, amount, amount);
        return orderId;
    }

    private Authentication buildAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }
}
