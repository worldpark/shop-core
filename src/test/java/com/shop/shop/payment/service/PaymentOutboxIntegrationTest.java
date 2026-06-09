package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.OrderCompletedEvent;
import com.shop.shop.payment.dto.PaymentResponse;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 결제 확정 → OrderCompletedEvent 발행 통합 테스트 (실 PostgreSQL).
 *
 * <p>검증:
 * <ul>
 *   <li>결제 확정 커밋 시 OrderCompletedEvent 1건 발행</li>
 *   <li>이벤트 페이로드 전 필수 필드 검증 (revision #4):
 *       orderId·orderNumber·memberId·memberEmail·memberName·totalAmount·currency·eventId·occurredAt·orderedAt
 *       + items[].productId·items[].productName·items[].quantity·items[].unitPrice</li>
 *   <li>items 스냅샷 필드(productName/quantity/unitPrice)가 order_items 주문 시점 값과 일치 (revision #4)</li>
 *   <li>payments 행 status=paid, orders 행 status=paid (원자 커밋)</li>
 *   <li>멱등 재요청 시 OrderCompletedEvent 중복 발행 없음 (eventPublished=false)</li>
 *   <li>시스템 오류(강제 예외) 시 payments·orders·event_publication ready row 모두 롤백 (부분 반영 없음)</li>
 * </ul>
 *
 * <p>외부 의존 모킹:
 * <ul>
 *   <li>MemberDirectory → 연락처 픽스처</li>
 *   <li>ProductOrderCatalog → variantId→productId 스냅샷 픽스처</li>
 *   <li>PaymentGatewayPort → @MockitoSpyBean (정상 경로는 실제 MockPaymentGateway 동작, 롤백 케이스는 예외 주입)</li>
 *   <li>Kafka 비활성: spring.modulith.events.externalization.enabled=false</li>
 * </ul>
 *
 * <p>OrderCompletedEvent 캡처: 테스트 전용 @Component CaptureListener가
 * @TransactionalEventListener(AFTER_COMMIT) 으로 커밋 완료 후에만 이벤트를 인-메모리 리스트에 저장한다.
 * AFTER_COMMIT 기반 캡처를 사용하므로 롤백 시 리스너가 호출되지 않아 "롤백됐지만 캡처됨" 오탐을 방지한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PaymentOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class PaymentOutboxIntegrationTest {

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
     * PaymentGatewayPort Spy: 정상 경로에서는 실제 MockPaymentGateway 동작을 그대로 사용하고,
     * 롤백 케이스에서만 doThrow()로 예외를 주입한다.
     * @MockitoSpyBean은 각 테스트 종료 후 자동으로 reset되므로 정상 테스트에 영향 없음.
     */
    @MockitoSpyBean
    private PaymentGatewayPort paymentGatewayPort;

    @BeforeEach
    void clearEvents() {
        captureListener.clear();
    }

    // =========================================================
    // 테스트
    // =========================================================

    @Test
    @DisplayName("결제 확정 커밋 시 OrderCompletedEvent 1건 발행 + 페이로드 전 필수 필드 검증 (revision #4)")
    void pay_onCommit_publishesOrderCompletedEvent() {
        // given
        long userId = insertUser("outbox1@test.com");
        long variantId = insertVariantWithStock(10, true);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);

        // order_items 스냅샷 기준값 (revision #4: productName/quantity/unitPrice 스냅샷 검증)
        String snapshotProductName = "테스트상품";
        int snapshotQuantity = 1;
        BigDecimal snapshotUnitPrice = BigDecimal.valueOf(10000);

        configMocks(userId, variantId, productId, snapshotUnitPrice);

        long orderId = placeOrder(userId, variantId, snapshotProductName, snapshotQuantity, snapshotUnitPrice);
        String orderNumber = jdbc.queryForObject(
                "SELECT order_number FROM orders WHERE id=?", String.class, orderId);

        // when
        Authentication auth = buildAuth(userId);
        PaymentResponse response = paymentServiceResponse.pay(auth, orderId, null);

        // then — payments/orders 원자 커밋
        assertThat(response.status()).isEqualTo("paid");
        assertThat(response.pgTransactionId()).startsWith("MOCK-");

        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("paid");

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("paid");

        // OrderCompletedEvent 1건 발행 검증 (AFTER_COMMIT 캡처 — 커밋 완료 후만 호출)
        List<OrderCompletedEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);

        OrderCompletedEvent event = events.get(0);

        // 공통 봉투 필드
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();

        // 주문/회원 필드
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.orderNumber()).isEqualTo(orderNumber);
        assertThat(event.memberId()).isEqualTo(userId);
        assertThat(event.memberEmail()).isEqualTo("test@example.com");
        assertThat(event.memberName()).isEqualTo("테스트유저");
        assertThat(event.orderedAt()).isNotNull();

        // 금액/통화
        assertThat(event.totalAmount()).isEqualTo(snapshotUnitPrice.longValue() * snapshotQuantity);
        assertThat(event.currency()).isEqualTo("KRW");

        // items 스냅샷 필드 검증 (revision #4: productName/quantity/unitPrice = order_items 주문 시점 값)
        assertThat(event.items()).hasSize(1);
        OrderCompletedEvent.Item item = event.items().get(0);
        assertThat(item.productId()).isEqualTo(productId);
        assertThat(item.productName()).isEqualTo(snapshotProductName);   // order_items 스냅샷
        assertThat(item.quantity()).isEqualTo(snapshotQuantity);          // order_items 스냅샷
        assertThat(item.unitPrice()).isEqualTo(snapshotUnitPrice.longValue()); // order_items 스냅샷
    }

    @Test
    @DisplayName("멱등 재요청 시 OrderCompletedEvent 중복 발행 없음")
    void pay_idempotentReRequest_noExtraEventPublished() {
        // given
        long userId = insertUser("outbox2@test.com");
        long variantId = insertVariantWithStock(10, true);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);

        configMocks(userId, variantId, productId, BigDecimal.valueOf(10000));

        long orderId = placeOrder(userId, variantId, "테스트상품", 1, BigDecimal.valueOf(10000));
        Authentication auth = buildAuth(userId);

        // 최초 결제
        paymentServiceResponse.pay(auth, orderId, null);
        int countAfterFirst = captureListener.size();

        // when — 멱등 재요청
        paymentServiceResponse.pay(auth, orderId, null);

        int countAfterSecond = captureListener.size();

        // then — 이벤트 추가 발행 없음 (멱등 경로는 이벤트 재발행 안 함)
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("시스템 오류(강제 예외) 시 payments·orders·event_publication 모두 롤백 — 부분 반영 없음")
    void pay_systemError_rollbacksAllChanges() {
        // given
        long userId = insertUser("outbox3@test.com");
        long variantId = insertVariantWithStock(10, true);
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);

        configMocks(userId, variantId, productId, BigDecimal.valueOf(10000));

        long orderId = placeOrder(userId, variantId, "테스트상품", 1, BigDecimal.valueOf(10000));
        Authentication auth = buildAuth(userId);

        // PG 승인(5단계)에서 RuntimeException 주입 → 4단계 ready row INSERT 이후 예외 발생
        // → 단일 @Transactional 범위의 payments(ready)/orders/event_publication 전체 롤백
        doThrow(new RuntimeException("테스트 시스템 오류 — PG 호출 실패"))
                .when(paymentGatewayPort).authorize(org.mockito.ArgumentMatchers.any());

        // when — 강제 예외 발생 확인
        assertThatThrownBy(() -> paymentServiceResponse.pay(auth, orderId, null))
                .isInstanceOf(RuntimeException.class);

        // then — 모든 DB 변경이 롤백됨 (부분 반영 없음)

        // payments 행 미생성 (ready row 포함 롤백)
        long paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId);
        assertThat(paymentCount)
                .as("시스템 오류 롤백 시 payments row(ready 포함)가 존재하지 않아야 한다")
                .isEqualTo(0);

        // orders status가 pending 유지 (주문 상태 미전이)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus)
                .as("시스템 오류 롤백 시 orders.status가 pending으로 유지되어야 한다")
                .isEqualTo("pending");

        // OrderCompletedEvent 미발행 (AFTER_COMMIT 캡처 — 커밋 안 됐으므로 리스너 미호출)
        assertThat(captureListener.size())
                .as("시스템 오류 롤백 시 OrderCompletedEvent가 발행되지 않아야 한다")
                .isEqualTo(0);
    }

    // =========================================================
    // 테스트 전용 이벤트 리스너 (OrderCompletedEvent 캡처)
    // =========================================================

    /**
     * 테스트 전용 OrderCompletedEvent 인-메모리 캡처 리스너.
     *
     * <p>@Component + @Import 방식 사용 (컴포넌트 스캔에 추가됨).
     * @TransactionalEventListener(AFTER_COMMIT) 으로 트랜잭션 커밋 완료 후에만 이벤트를 캡처한다.
     * 트랜잭션이 롤백되면 이 리스너가 호출되지 않으므로 "롤백됐지만 이벤트 캡처됨" 오탐이 방지된다.
     */
    @Component
    static class CaptureListener {
        private final List<OrderCompletedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(OrderCompletedEvent event) {
            events.add(event);
        }

        public List<OrderCompletedEvent> getEvents() {
            return Collections.unmodifiableList(events);
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
                variantId, productId, "테스트상품", null, List.of(),
                price, true, 100, "ON_SALE", true);

        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snapshot));
    }

    /**
     * 주문 + 주문 항목 삽입. productName/quantity/unitPrice를 파라미터로 받아
     * order_items 스냅샷 검증 시 기준값으로 활용할 수 있게 한다 (revision #4).
     */
    private long placeOrder(long userId, long variantId, String productName, int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-TEST-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);

        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);

        return orderId;
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock, boolean isActive) {
        String sku = "OUTBOX-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('Outbox테스트상품', '설명', 10000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 10000, ?, ?)",
                productId, sku, stock, isActive);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private Authentication buildAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }
}
