package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.event.ShippingStartedEvent;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 배송 시작(ship) Outbox 통합 테스트 — Task 020 BLOCKER 1.
 *
 * <p>검증:
 * <ul>
 *   <li>preparing 배송 ship 커밋 시 event_publication에 ShippingStartedEvent 정확히 1건 저장
 *       + 개정 payload 스키마(shipmentId·items[]·carrier·trackingNumber·memberEmail·memberName·shippedAt) 충족</li>
 *   <li>멀티 배송: 한 주문에 배송 2건일 때 각 ship마다 이벤트 1건(해당 배송 항목만), 첫 ship 주문
 *       preparing→shipping rollup, 둘째 ship 주문 status 불변(shipping)</li>
 *   <li>멱등: 같은 배송 ship 재호출 시 event_publication 증가 0(이벤트 재발행 없음)</li>
 *   <li>시스템 오류(ProductOrderCatalog 예외) 시 상태·이벤트 부분 반영 없음(전체 롤백)</li>
 * </ul>
 *
 * <p>외부 의존 모킹: MemberDirectory, ProductOrderCatalog.
 * Kafka 비활성: spring.modulith.events.externalization.enabled=false.
 * 이벤트 캡처: CaptureListener @TransactionalEventListener(AFTER_COMMIT).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OrderFulfillmentShipOutboxIntegrationTest.CaptureListener.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class OrderFulfillmentShipOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

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
                .thenReturn(new MemberContact("ship-test@example.com", "배송테스터"));
    }

    // ============================================================
    // ship 커밋 + Outbox 발행 + payload 스키마 검증
    // ============================================================

    @Test
    @DisplayName("preparing 배송 ship 커밋 시 ShippingStartedEvent 1건 + 개정 payload 스키마 검증")
    void ship_preparing_publishesShippingStartedEventWithCorrectPayload() {
        // given
        long userId = insertUser("outbox-ship1@test.com");
        long variantId = insertVariant(10);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPaidOrder(userId, variantId, "배송테스트상품", 2, BigDecimal.valueOf(5000));
        String orderNumber = getOrderNumber(orderId);

        // createShipment → preparing (paid→preparing rollup)
        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = getShipmentId(orderId);
        configProductMock(variantId, productId, "배송테스트상품", BigDecimal.valueOf(5000));

        // when
        orderFulfillmentService.ship(shipmentId, "CJ대한통운", "TRK-OUTBOX-001");

        // then: ShippingStartedEvent AFTER_COMMIT 발행 1건
        List<ShippingStartedEvent> events = captureListener.getEvents();
        assertThat(events).hasSize(1);

        ShippingStartedEvent event = events.get(0);

        // 공통 봉투
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();

        // 주문/배송 식별자
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.orderNumber()).isEqualTo(orderNumber);
        assertThat(event.shipmentId()).isEqualTo(shipmentId);  // 개정 스키마: shipmentId

        // 회원 정보
        assertThat(event.memberId()).isEqualTo(userId);
        assertThat(event.memberEmail()).isEqualTo("ship-test@example.com");
        assertThat(event.memberName()).isEqualTo("배송테스터");

        // 운송 정보
        assertThat(event.carrier()).isEqualTo("CJ대한통운");
        assertThat(event.trackingNumber()).isEqualTo("TRK-OUTBOX-001");
        assertThat(event.shippedAt()).isNotNull();

        // items[] — 개정 스키마: 이 배송에 포함된 항목만
        assertThat(event.items()).hasSize(1);
        ShippingStartedEvent.Item item = event.items().get(0);
        assertThat(item.productId()).isEqualTo(productId);
        assertThat(item.productName()).isEqualTo("배송테스트상품");
        assertThat(item.quantity()).isEqualTo(2);

        // shipments.status = shipping
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("shipping");

        // orders.status = shipping (rollup)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("shipping");
    }

    // ============================================================
    // 멀티 배송 — 각 ship마다 이벤트 1건(해당 항목만), 주문 rollup 1회
    // ============================================================

    @Test
    @DisplayName("멀티 배송: 각 ship마다 이벤트 1건(해당 배송 항목만), 첫 ship 주문 rollup, 둘째 ship 주문 status 불변")
    void ship_multiShipment_eachShipPublishesOneEventWithOwnItems() {
        // given: 2개 항목 주문
        long userId = insertUser("multi-ship1@test.com");
        long variantId1 = insertVariant(5);
        long variantId2 = insertVariant(5);
        long productId1 = getProductIdByVariant(variantId1);
        long productId2 = getProductIdByVariant(variantId2);
        long orderId = insertPaidOrderWithTwoItems(userId, variantId1, "상품1", variantId2, "상품2");

        List<Long> itemIds = getOrderItemIds(orderId);
        assertThat(itemIds).hasSize(2);

        // 첫 번째 배송 생성 (항목1만)
        orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(0)));
        long shipmentId1 = getShipmentId(orderId);

        // 두 번째 배송 생성 (항목2)
        orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(1)));
        long shipmentId2 = getLatestShipmentId(orderId);

        // 첫 번째 배송 ship — 항목1만 포함
        configProductMockForTwo(variantId1, productId1, "상품1", variantId2, productId2, "상품2");
        orderFulfillmentService.ship(shipmentId1, "CJ대한통운", "TRK-MULTI-001");

        // then: 첫 ship 이후 주문 preparing→shipping rollup
        String orderStatusAfterFirst = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterFirst).isEqualTo("shipping");

        List<ShippingStartedEvent> eventsAfterFirst = captureListener.getEvents();
        assertThat(eventsAfterFirst).hasSize(1);
        assertThat(eventsAfterFirst.get(0).shipmentId()).isEqualTo(shipmentId1);
        assertThat(eventsAfterFirst.get(0).items()).hasSize(1);
        assertThat(eventsAfterFirst.get(0).items().get(0).productId()).isEqualTo(productId1);

        captureListener.clear();

        // 두 번째 배송 ship — 항목2만 포함
        configProductMockForTwo(variantId1, productId1, "상품1", variantId2, productId2, "상품2");
        orderFulfillmentService.ship(shipmentId2, "한진택배", "TRK-MULTI-002");

        // then: 둘째 ship 이후 주문 status 불변(shipping)
        String orderStatusAfterSecond = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterSecond).isEqualTo("shipping");

        List<ShippingStartedEvent> eventsAfterSecond = captureListener.getEvents();
        assertThat(eventsAfterSecond).hasSize(1);
        assertThat(eventsAfterSecond.get(0).shipmentId()).isEqualTo(shipmentId2);
        assertThat(eventsAfterSecond.get(0).items()).hasSize(1);
        assertThat(eventsAfterSecond.get(0).items().get(0).productId()).isEqualTo(productId2);
    }

    // ============================================================
    // 멱등 — 재호출 시 이벤트 재발행 없음
    // ============================================================

    @Test
    @DisplayName("멱등: 같은 배송 ship 재호출 시 event_publication 증가 0, 이벤트 재발행 없음")
    void ship_idempotent_noAdditionalEventOnReCall() {
        // given
        long userId = insertUser("idempotent-ship1@test.com");
        long variantId = insertVariant(5);
        long productId = getProductIdByVariant(variantId);
        long orderId = insertPaidOrder(userId, variantId, "멱등상품", 1, BigDecimal.valueOf(3000));

        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = getShipmentId(orderId);
        configProductMock(variantId, productId, "멱등상품", BigDecimal.valueOf(3000));

        // 최초 ship
        orderFulfillmentService.ship(shipmentId, "CJ대한통운", "TRK-IDEMPOTENT-001");
        int captureCountAfterFirst = captureListener.size();

        // when: 멱등 재호출 (다른 carrier/trackingNumber로도 기존 값 유지, 정합4)
        orderFulfillmentService.ship(shipmentId, "다른택배사", "TRK-DIFF-999");

        // then: AFTER_COMMIT 리스너 추가 호출 없음 (이벤트 재발행 없음)
        assertThat(captureListener.size()).isEqualTo(captureCountAfterFirst);

        // shipments.status 여전히 shipping (기존 값 유지)
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("shipping");

        // carrier 기존 값 유지 (덮어쓰지 않음, 정합4)
        String carrier = jdbc.queryForObject(
                "SELECT carrier FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(carrier).isEqualTo("CJ대한통운");
    }

    // ============================================================
    // 시스템 오류 — 원자성 롤백
    // ============================================================

    @Test
    @DisplayName("시스템 오류(ProductOrderCatalog 예외) 시 shipments 상태·event_publication 부분 반영 없음")
    void ship_systemError_rollbacksAllChanges() {
        // given
        long userId = insertUser("rollback-ship1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertPaidOrder(userId, variantId, "롤백배송상품", 1, BigDecimal.valueOf(2000));

        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = getShipmentId(orderId);

        // ProductOrderCatalog → RuntimeException 강제 실패 (P2 단계에서 예외 발생 → 전체 롤백)
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenThrow(new RuntimeException("테스트 시스템 오류 — 롤백 검증"));

        // when
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orderFulfillmentService.ship(shipmentId, "CJ대한통운", "TRK-ROLLBACK"))
                .isInstanceOf(RuntimeException.class);

        // then: shipments.status 여전히 preparing (롤백)
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("preparing");

        // orders.status 여전히 preparing (롤백 — rollup 없음)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("preparing");

        // AFTER_COMMIT 리스너 미실행 (트랜잭션 롤백이므로 이벤트 미발행)
        assertThat(captureListener.size()).isEqualTo(0);
    }

    // ============================================================
    // 테스트 전용 이벤트 리스너
    // ============================================================

    @Component
    static class CaptureListener {
        private final List<ShippingStartedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(ShippingStartedEvent event) {
            events.add(event);
        }

        public List<ShippingStartedEvent> getEvents() {
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

    /**
     * 두 variant를 포함하는 mock 설정 — 멀티 배송 테스트용.
     * getOrderableSnapshots는 variantId 집합을 인자로 받으므로 두 스냅샷을 모두 반환한다.
     */
    private void configProductMockForTwo(long variantId1, long productId1, String name1,
                                          long variantId2, long productId2, String name2) {
        OrderableVariantSnapshot snap1 = new OrderableVariantSnapshot(
                variantId1, productId1, name1, null, List.of(),
                BigDecimal.valueOf(5000), true, 100, "ON_SALE", true);
        OrderableVariantSnapshot snap2 = new OrderableVariantSnapshot(
                variantId2, productId2, name2, null, List.of(),
                BigDecimal.valueOf(5000), true, 100, "ON_SALE", true);
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenReturn(List.of(snap1, snap2));
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariant(int stock) {
        String sku = "SHIP-OUTBOX-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('배송Outbox테스트상품', '설명', 5000, 'ON_SALE')");
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
        String orderNumber = "ORD-SHIP-OUTBOX-" + System.nanoTime();
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

    private long insertPaidOrderWithTwoItems(long userId,
                                              long variantId1, String name1,
                                              long variantId2, String name2) {
        String orderNumber = "ORD-SHIP-MULTI-" + System.nanoTime();
        BigDecimal amount = BigDecimal.valueOf(10000);
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'paid', ?, 0, 0, ?, '수령인', '010-1234-5678', '12345', '서울시')",
                userId, orderNumber, amount, amount);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, 5000, 1, 5000)", orderId, variantId1, name1);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, 5000, 1, 5000)", orderId, variantId2, name2);
        return orderId;
    }

    private String getOrderNumber(long orderId) {
        return jdbc.queryForObject(
                "SELECT order_number FROM orders WHERE id=?", String.class, orderId);
    }

    private long getShipmentId(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id ASC LIMIT 1",
                Long.class, orderId);
    }

    private long getLatestShipmentId(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId);
    }

    private List<Long> getOrderItemIds(long orderId) {
        return jdbc.queryForList(
                "SELECT id FROM order_items WHERE order_id=? ORDER BY id", Long.class, orderId);
    }

}
