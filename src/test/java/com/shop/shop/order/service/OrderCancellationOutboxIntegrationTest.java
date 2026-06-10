package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.OrderCancelledEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.order.spi.OrderCancellation.OrderCancellationResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 주문 취소 Outbox 발행 + 재고 복원 + 원자성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>취소 커밋 시 OrderCancelledEvent 1건 발행 + 페이로드 필수 필드 검증</li>
 *   <li>재고 복원: variant_stock.stock += quantity (order_items 수량만큼 증가)</li>
 *   <li>멱등 재취소: ALREADY_CANCELLED 반환, 이벤트 재발행 없음, 재고 중복 복원 없음</li>
 *   <li>variantId null 항목 skip — 나머지 정상 복원 (best-effort)</li>
 *   <li>시스템 오류(강제 예외) 시 orders/variant_stock/event_publication 부분 반영 없음 (원자성)</li>
 * </ul>
 *
 * <p>외부 의존 모킹: MemberDirectory, ProductOrderCatalog.
 * Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 * 이벤트 캡처: CaptureListener @TransactionalEventListener(AFTER_COMMIT).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OrderCancellationOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class OrderCancellationOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderCancellation orderCancellation;

    @Autowired
    private OrderRepository orderRepository;

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
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // 취소 커밋 + Outbox 발행
    // ============================================================

    @Test
    @DisplayName("미결제 취소 커밋 시 OrderCancelledEvent 1건 발행 + 페이로드 필수 필드 검증")
    void cancel_pending_publishesOrderCancelledEvent() {
        // given
        long userId = insertUser("outbox-cancel1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "상품A", 2, BigDecimal.valueOf(5000));
        String orderNumber = getOrderNumber(orderId);

        configProductMock(variantId, productId, "상품A", BigDecimal.valueOf(5000));

        // when
        OrderCancellationResult result = orderCancellation.cancel(
                orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(result.orderStatus()).isEqualTo("cancelled");

        // orders.status = cancelled
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("cancelled");

        // OrderCancelledEvent 1건 발행 (AFTER_COMMIT)
        List<OrderCancelledEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);

        OrderCancelledEvent event = events.get(0);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.orderNumber()).isEqualTo(orderNumber);
        assertThat(event.memberId()).isEqualTo(userId);
        assertThat(event.memberEmail()).isEqualTo("test@example.com");
        assertThat(event.memberName()).isEqualTo("테스트유저");
        assertThat(event.refunded()).isFalse();
        assertThat(event.refundedAmount()).isZero();
        assertThat(event.currency()).isEqualTo("KRW");
        assertThat(event.cancelledAt()).isNotNull();

        assertThat(event.items()).hasSize(1);
        assertThat(event.items().get(0).productId()).isEqualTo(productId);
        assertThat(event.items().get(0).productName()).isEqualTo("상품A");
        assertThat(event.items().get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("결제완료 취소 커밋 시 OrderCancelledEvent(refunded=true) 발행 + orders.status=refunded")
    void cancel_paid_publishesOrderCancelledEventRefunded() {
        // given
        long userId = insertUser("outbox-cancel2@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "상품B", 1, BigDecimal.valueOf(10000));

        // orders.status=paid로 갱신 (결제완료 취소 시뮬레이션)
        jdbc.update("UPDATE orders SET status='paid' WHERE id=?", orderId);

        configProductMock(variantId, productId, "상품B", BigDecimal.valueOf(10000));

        // when
        OrderCancellationResult result = orderCancellation.cancel(
                orderId, userId, new RefundInfo(true, 10000L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);
        assertThat(result.orderStatus()).isEqualTo("refunded");

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("refunded");

        List<OrderCancelledEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).refunded()).isTrue();
        assertThat(events.get(0).refundedAmount()).isEqualTo(10000L);
    }

    // ============================================================
    // 재고 복원 검증
    // ============================================================

    @Test
    @DisplayName("재고 복원: 취소 후 variant_stock.stock += quantity (order_items 수량만큼 증가)")
    void cancel_stockRestored_byOrderItemQuantity() {
        // given
        long userId = insertUser("stock-restore1@test.com");
        long variantId = insertVariantWithStock(5); // 초기 재고 5
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "재고복원상품", 3, BigDecimal.valueOf(2000));

        configProductMock(variantId, productId, "재고복원상품", BigDecimal.valueOf(2000));

        // when
        orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then: 5 + 3 = 8
        Integer stock = jdbc.queryForObject(
                "SELECT pv.stock FROM product_variants pv WHERE pv.id=?", Integer.class, variantId);
        assertThat(stock).isEqualTo(8);
    }

    // ============================================================
    // 멱등 재취소 — ALREADY_CANCELLED
    // ============================================================

    @Test
    @DisplayName("멱등 재취소: ALREADY_CANCELLED 반환, 이벤트 재발행 없음, 재고 중복 복원 없음")
    void cancel_alreadyCancelled_idempotentNoDoubleRestore() {
        // given
        long userId = insertUser("idempotent-cancel1@test.com");
        long variantId = insertVariantWithStock(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "멱등상품", 2, BigDecimal.valueOf(3000));

        configProductMock(variantId, productId, "멱등상품", BigDecimal.valueOf(3000));

        // 최초 취소
        orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW"));
        int stockAfterFirst = getStock(variantId); // 5 + 2 = 7
        int eventCountAfterFirst = captureListener.size();

        // when — 멱등 재취소
        OrderCancellationResult result = orderCancellation.cancel(
                orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.ALREADY_CANCELLED);

        // 이벤트 재발행 없음 (ALREADY_CANCELLED 경로는 발행 스킵)
        assertThat(captureListener.size()).isEqualTo(eventCountAfterFirst);

        // 재고 중복 복원 없음 (5+2=7에서 더 증가하지 않음)
        assertThat(getStock(variantId)).isEqualTo(stockAfterFirst);
    }

    // ============================================================
    // variantId null 항목 skip
    // ============================================================

    @Test
    @DisplayName("variantId null 항목 skip — 나머지 정상 복원, 이벤트 items에서 제외")
    void cancel_nullVariantIdItem_skipAndRestoreOthers() {
        // given
        long userId = insertUser("null-variant1@test.com");
        long variantId = insertVariantWithStock(10); // 정상 항목용
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrderWithNullVariantItem(userId, variantId, productId);

        configProductMock(variantId, productId, "정상상품", BigDecimal.valueOf(4000));

        // when
        OrderCancellationResult result = orderCancellation.cancel(
                orderId, userId, new RefundInfo(false, 0L, "KRW"));

        // then
        assertThat(result.outcome()).isEqualTo(OrderCancellation.Outcome.CANCELLED);

        // 정상 variantId의 재고만 복원됨: 10 + 2 = 12
        assertThat(getStock(variantId)).isEqualTo(12);

        // 이벤트 items에 null variant 항목 제외 → 1개
        List<OrderCancelledEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).items()).hasSize(1);
        assertThat(events.get(0).items().get(0).productId()).isEqualTo(productId);
    }

    // ============================================================
    // 원자성 — 시스템 오류 롤백
    // ============================================================

    @Test
    @DisplayName("시스템 오류(강제 예외) 시 orders/variant_stock 부분 반영 없음 (원자성)")
    void cancel_systemError_rollbacksAllChanges() {
        // given
        long userId = insertUser("rollback-cancel1@test.com");
        long variantId = insertVariantWithStock(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "롤백상품", 2, BigDecimal.valueOf(3000));

        // ProductOrderCatalog를 RuntimeException으로 강제 실패
        // → 재고 복원·이벤트 발행 이전 예외 발생 → 전체 롤백
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenThrow(new RuntimeException("테스트 시스템 오류 — 롤백 검증"));

        // when
        assertThatThrownBy(() ->
                orderCancellation.cancel(orderId, userId, new RefundInfo(false, 0L, "KRW")))
                .isInstanceOf(RuntimeException.class);

        // then — orders.status 여전히 pending (롤백)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("pending");

        // variant_stock 미변경 (5 그대로)
        assertThat(getStock(variantId)).isEqualTo(5);

        // OrderCancelledEvent 미발행
        assertThat(captureListener.size()).isEqualTo(0);
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
        String sku = "CANCEL-OUTBOX-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('취소Outbox테스트상품', '설명', 5000, 'ON_SALE')");
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
        String orderNumber = "ORD-CANCEL-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, lineAmount, lineAmount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    /**
     * null variantId 항목(삭제된 variant) 포함 주문 생성.
     * variantId 항목(정상): quantity=2
     * null variantId 항목(삭제됨): quantity=1
     */
    private long insertPendingOrderWithNullVariantItem(long userId, long variantId, long productId) {
        String orderNumber = "ORD-NULL-VAR-" + System.nanoTime();
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', 10000, 0, 0, 10000, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);

        // 정상 variantId 항목
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, '정상상품', 4000, 2, 8000)", orderId, variantId);

        // null variantId 항목 (ON DELETE SET NULL 시뮬레이션)
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, NULL, '삭제된상품', 2000, 1, 2000)", orderId);

        return orderId;
    }

    private String getOrderNumber(long orderId) {
        return jdbc.queryForObject(
                "SELECT order_number FROM orders WHERE id=?", String.class, orderId);
    }

    private int getStock(long variantId) {
        return jdbc.queryForObject(
                "SELECT pv.stock FROM product_variants pv WHERE pv.id=?", Integer.class, variantId);
    }
}
