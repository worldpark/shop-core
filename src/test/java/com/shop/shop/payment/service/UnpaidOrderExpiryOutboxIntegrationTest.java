package com.shop.shop.payment.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.OrderCancelledEvent;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 미결제 주문 만료 → OrderCancelledEvent Outbox + 재고 복원 + 원자성 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>TTL 초과 pending(결제 row 없음) 만료 → orders cancelled + 재고 복원 + event_publication 1건(refunded=false) 원자 커밋</li>
 *   <li>TTL 초과 pending(결제 row ready) 만료 → payments cancelled + orders cancelled + 재고 복원 + 이벤트 1건</li>
 *   <li>TTL 미만 pending → 불변(만료 대상 아님 — threshold 미충족)</li>
 *   <li>paid/이행 주문(created_at 과거여도) → 멱등 skip, 불변, 이벤트 0</li>
 *   <li>멱등 재실행(expirePendingOrder 2회) → 재고 불변·이벤트 추가 없음</li>
 *   <li>시스템 오류 강제(전체 롤백) → orders/payments/stock/event_publication 부분 반영 없음</li>
 *   <li>null variantId 항목 skip → 나머지 정상 복원, 만료 성공, 이벤트 items 제외</li>
 * </ul>
 *
 * <p>018 통합 테스트 프로파일 답습 (C12).
 * created_at은 SQL로 과거 세팅해 만료 상황 생성.
 * 스케줄러 비활성(application.yml: enabled=false) — expirePendingOrder 직접 호출로 검증.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(UnpaidOrderExpiryOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"  // 스케줄러 빈 비활성 (verification-gate §4)
})
class UnpaidOrderExpiryOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptureListener captureListener;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @MockitoSpyBean
    private com.shop.shop.inventory.spi.InventoryStockPort inventoryStockPort;

    @BeforeEach
    void setUp() {
        captureListener.clear();
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트유저"));
    }

    // ============================================================
    // TTL 초과 pending — 결제 row 없음
    // ============================================================

    @Test
    @DisplayName("TTL 초과 pending(결제 row 없음) → orders cancelled + 재고 복원 + event_publication 1건(refunded=false) 원자 커밋")
    void expirePendingOrder_pendingNoPayment_atomicCommit() {
        // given
        long userId = insertUser("expiry-outbox1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "만료상품", 2, BigDecimal.valueOf(5000));
        // created_at을 TTL 초과 과거로 세팅
        setCreatedAtPast(orderId, 60);  // 60분 전

        configProductMock(variantId, productId, "만료상품", BigDecimal.valueOf(5000));

        // when
        paymentService.expirePendingOrder(orderId);

        // then — orders cancelled
        String orderStatus = getOrderStatus(orderId);
        assertThat(orderStatus).isEqualTo("cancelled");

        // 재고 복원: 10 + 2 = 12
        assertThat(getStock(variantId)).isEqualTo(12);

        // OrderCancelledEvent 1건(refunded=false, refundedAmount=0)
        List<OrderCancelledEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).refunded()).isFalse();
        assertThat(events.get(0).refundedAmount()).isZero();
        assertThat(events.get(0).currency()).isEqualTo("KRW");
        assertThat(events.get(0).orderId()).isEqualTo(orderId);
        assertThat(events.get(0).memberId()).isEqualTo(userId);

        // event_publication 저장 확인
        Integer epCount = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication", Integer.class);
        assertThat(epCount).isGreaterThanOrEqualTo(1);
    }

    // ============================================================
    // TTL 초과 pending — 결제 row ready
    // ============================================================

    @Test
    @DisplayName("TTL 초과 pending(결제 row ready) → payments cancelled + orders cancelled + 재고 복원 + 이벤트 1건")
    void expirePendingOrder_pendingWithReadyPayment_atomicCommit() {
        // given
        long userId = insertUser("expiry-outbox2@test.com");
        long variantId = insertVariantWithStock(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "결제row만료상품", 1, BigDecimal.valueOf(10000));
        setCreatedAtPast(orderId, 60);

        // payments ready row 삽입
        insertPaymentReady(orderId, BigDecimal.valueOf(10000));

        configProductMock(variantId, productId, "결제row만료상품", BigDecimal.valueOf(10000));

        // when
        paymentService.expirePendingOrder(orderId);

        // then — orders cancelled
        assertThat(getOrderStatus(orderId)).isEqualTo("cancelled");

        // payments cancelled
        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM payments WHERE order_id=?", String.class, orderId);
        assertThat(paymentStatus).isEqualTo("cancelled");

        // 재고 복원: 5 + 1 = 6
        assertThat(getStock(variantId)).isEqualTo(6);

        // 이벤트 1건
        assertThat(captureListener.getEvents()).hasSize(1);
    }

    // ============================================================
    // paid/이행 주문 → 멱등 skip
    // ============================================================

    @Test
    @DisplayName("paid 주문(created_at 과거) → 멱등 skip, 불변, 이벤트 0")
    void expirePendingOrder_paidOrder_idempotentSkip() {
        // given
        long userId = insertUser("expiry-paid1@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "paid상품", 1, BigDecimal.valueOf(5000));
        setCreatedAtPast(orderId, 60);
        // orders.status = paid로 변경
        jdbc.update("UPDATE orders SET status='paid' WHERE id=?", orderId);

        // when
        paymentService.expirePendingOrder(orderId);

        // then — 불변
        assertThat(getOrderStatus(orderId)).isEqualTo("paid");
        assertThat(getStock(variantId)).isEqualTo(5);  // 재고 미복원
        assertThat(captureListener.getEvents()).isEmpty();
    }

    // ============================================================
    // 멱등 재실행
    // ============================================================

    @Test
    @DisplayName("멱등 재실행(expirePendingOrder 2회) → 재고 불변·이벤트 추가 없음")
    void expirePendingOrder_idempotentRerun_noDoubleRestore() {
        // given
        long userId = insertUser("expiry-idem1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrder(userId, variantId, "멱등만료상품", 2, BigDecimal.valueOf(3000));
        setCreatedAtPast(orderId, 60);

        configProductMock(variantId, productId, "멱등만료상품", BigDecimal.valueOf(3000));

        // 1회 실행
        paymentService.expirePendingOrder(orderId);
        int stockAfterFirst = getStock(variantId);  // 12
        int eventCountAfterFirst = captureListener.size();  // 1

        // when — 2회 실행 (멱등)
        paymentService.expirePendingOrder(orderId);

        // then — 재고·이벤트 불변
        assertThat(getStock(variantId)).isEqualTo(stockAfterFirst);
        assertThat(captureListener.size()).isEqualTo(eventCountAfterFirst);
    }

    // ============================================================
    // 원자성 — 시스템 오류 롤백
    // ============================================================

    @Test
    @DisplayName("시스템 오류 강제 → 해당 주문 전체 롤백 (orders/stock/event_publication 부분 반영 없음)")
    void expirePendingOrder_systemError_rollbacksAllChanges() {
        // given
        long userId = insertUser("expiry-rollback1@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "롤백만료상품", 2, BigDecimal.valueOf(2000));
        setCreatedAtPast(orderId, 60);

        // ProductOrderCatalog 강제 예외 → 재고 복원 전 실패 → 전체 롤백
        doThrow(new RuntimeException("테스트 시스템 오류"))
                .when(productOrderCatalog).getOrderableSnapshots(anyCollection());

        int epCountBefore = countEventPublication();

        // when
        assertThatThrownBy(() -> paymentService.expirePendingOrder(orderId))
                .isInstanceOf(RuntimeException.class);

        // then — orders pending 유지 (롤백)
        assertThat(getOrderStatus(orderId)).isEqualTo("pending");

        // 재고 미변경
        assertThat(getStock(variantId)).isEqualTo(5);

        // event_publication 미증가
        assertThat(countEventPublication()).isEqualTo(epCountBefore);

        // 이벤트 미발행
        assertThat(captureListener.getEvents()).isEmpty();
    }

    // ============================================================
    // null variantId 항목 skip
    // ============================================================

    @Test
    @DisplayName("null variantId 항목 skip → 나머지 정상 복원, 만료 성공, 이벤트 items 제외")
    void expirePendingOrder_nullVariantIdItem_skipAndRestore() {
        // given
        long userId = insertUser("expiry-null-var1@test.com");
        long variantId = insertVariantWithStock(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPendingOrderWithNullVariantItem(userId, variantId, productId);
        setCreatedAtPast(orderId, 60);

        configProductMock(variantId, productId, "정상상품", BigDecimal.valueOf(4000));

        // when
        paymentService.expirePendingOrder(orderId);

        // then — 만료 성공
        assertThat(getOrderStatus(orderId)).isEqualTo("cancelled");

        // 정상 variantId 재고만 복원: 10 + 2 = 12
        assertThat(getStock(variantId)).isEqualTo(12);

        // 이벤트 items에 null variant 제외 → 1개
        List<OrderCancelledEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).items()).hasSize(1);
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
        String sku = "EXPIRY-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('만료테스트상품', '설명', 5000, 'ON_SALE')");
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
        String orderNumber = "ORD-EXPIRY-" + System.nanoTime();
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

    private long insertPendingOrderWithNullVariantItem(long userId, long variantId, long productId) {
        String orderNumber = "ORD-EXPIRY-NULL-" + System.nanoTime();
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

    private void insertPaymentReady(long orderId, BigDecimal amount) {
        jdbc.update("INSERT INTO payments (order_id, method, amount, status) "
                + "VALUES (?, 'mock', ?, 'ready')", orderId, amount);
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

    private int countEventPublication() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
        return count != null ? count : 0;
    }
}
