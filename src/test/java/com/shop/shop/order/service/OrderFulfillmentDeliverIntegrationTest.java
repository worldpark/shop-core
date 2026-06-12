package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.order.dto.DeliverResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배송 완료(deliver) 통합 테스트 (실 PostgreSQL, Testcontainers + Spring Modulith).
 *
 * <p>검증:
 * <ul>
 *   <li>단일 배송 shipping→delivered 전이 원자성 + 주문 rollup delivered</li>
 *   <li>멀티 배송 마지막 완료가 주문 delivered 트리거</li>
 *   <li>미배정 항목 있으면 주문 shipping 유지</li>
 *   <li>멱등 재호출 상태 불변 (event_publication 무변화)</li>
 *   <li>단일 배송 주문 이미 delivered → 재-deliver 멱등 200, 주문/shipment 불변 (방어 가드 409 아님, revision-2)</li>
 *   <li>이벤트 미발행: event_publication 무변화 (배송완료 이벤트 없음)</li>
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
class OrderFulfillmentDeliverIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private JdbcTemplate jdbc;

    // ============================================================
    // 단일 배송 완료 + 주문 rollup
    // ============================================================

    @Test
    @DisplayName("단일 배송 deliver → shipments.status=delivered + deliveredAt 기록 + orders.status=delivered rollup")
    void deliver_singleShipment_deliveredAndOrderRollup() {
        long userId = insertUser("deliver-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertShippingOrder(userId, variantId, "상품A", 1, BigDecimal.valueOf(5000));

        // createShipment → preparing, ship → shipping
        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        shipToShipping(shipmentId);

        int beforeEventCount = countEventPublications();

        // when
        DeliverResponse result = orderFulfillmentService.deliver(shipmentId);

        // then: 응답
        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.shipment().deliveredAt()).isNotNull();
        assertThat(result.orderDelivered()).isTrue();

        // DB 검증: shipments.status=delivered, deliveredAt 기록
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("delivered");

        String deliveredAt = jdbc.queryForObject(
                "SELECT delivered_at FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(deliveredAt).isNotNull();

        // DB 검증: orders.status=delivered (rollup)
        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("delivered");

        // 이벤트 미발행 (배송완료 이벤트 없음)
        int afterEventCount = countEventPublications();
        assertThat(afterEventCount).isEqualTo(beforeEventCount);
    }

    // ============================================================
    // 멀티 배송 — 마지막 완료가 주문 delivered 트리거
    // ============================================================

    @Test
    @DisplayName("멀티 배송: 첫 배송 완료 → 주문 shipping 유지, 마지막 완료 → 주문 delivered rollup")
    void deliver_multiShipment_lastDeliverTriggersOrderRollup() {
        long userId = insertUser("deliver-multi-1@test.com");
        long variantId1 = insertVariant(5);
        long variantId2 = insertVariant(5);
        long orderId = insertPaidOrderWithTwoItems(userId, variantId1, variantId2);

        List<Long> itemIds = getOrderItemIds(orderId);
        assertThat(itemIds).hasSize(2);

        // 두 배송 먼저 생성 (주문이 paid/preparing 상태일 때)
        var resp1 = orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(0)));
        long shipmentId1 = resp1.shipmentId();

        var resp2 = orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(1)));
        long shipmentId2 = resp2.shipmentId();

        // 그 다음 둘 다 shipToShipping
        shipToShipping(shipmentId1);
        shipToShipping(shipmentId2);

        // 첫 배송 완료 → 주문 shipping 유지
        DeliverResponse result1 = orderFulfillmentService.deliver(shipmentId1);
        assertThat(result1.orderDelivered()).isFalse();

        String orderStatusAfterFirst = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterFirst).isEqualTo("shipping");

        // 마지막 배송 완료 → 주문 delivered rollup
        DeliverResponse result2 = orderFulfillmentService.deliver(shipmentId2);
        assertThat(result2.orderDelivered()).isTrue();

        String orderStatusAfterLast = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterLast).isEqualTo("delivered");
    }

    // ============================================================
    // 미배정 항목 → 주문 shipping 유지
    // ============================================================

    @Test
    @DisplayName("미배정 항목 있으면 생성된 배송 전부 delivered여도 주문 shipping 유지")
    void deliver_unassignedItems_orderRemainsShipping() {
        long userId = insertUser("deliver-unassigned-1@test.com");
        long variantId1 = insertVariant(5);
        long variantId2 = insertVariant(5);
        long orderId = insertPaidOrderWithTwoItems(userId, variantId1, variantId2);

        List<Long> itemIds = getOrderItemIds(orderId);

        // 첫 번째 항목만 배송 생성 + shipping (두 번째 항목은 미배정)
        var resp = orderFulfillmentService.createShipment(orderId, List.of(itemIds.get(0)));
        long shipmentId = resp.shipmentId();
        shipToShipping(shipmentId);

        // 배송 완료 → 전 배송 delivered이지만 미배정 항목 있으므로 주문 shipping 유지
        DeliverResponse result = orderFulfillmentService.deliver(shipmentId);
        assertThat(result.orderDelivered()).isFalse();

        String orderStatus = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("shipping");
    }

    // ============================================================
    // 멱등 — 재호출 상태 불변
    // ============================================================

    @Test
    @DisplayName("멱등: 같은 배송 deliver 재호출 → 상태 불변, deliveredAt 변경 없음")
    void deliver_idempotent_stateUnchanged() {
        long userId = insertUser("deliver-idem-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertShippingOrder(userId, variantId, "멱등상품", 1, BigDecimal.valueOf(3000));

        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        shipToShipping(shipmentId);

        // 최초 deliver
        orderFulfillmentService.deliver(shipmentId);

        String deliveredAtFirst = jdbc.queryForObject(
                "SELECT delivered_at FROM shipments WHERE id=?", String.class, shipmentId);
        String orderStatusFirst = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);

        // 멱등 재호출
        DeliverResponse result = orderFulfillmentService.deliver(shipmentId);

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isTrue();

        // DB 상태 불변
        String deliveredAtSecond = jdbc.queryForObject(
                "SELECT delivered_at FROM shipments WHERE id=?", String.class, shipmentId);
        String orderStatusSecond = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);

        assertThat(deliveredAtSecond).isEqualTo(deliveredAtFirst);
        assertThat(orderStatusSecond).isEqualTo(orderStatusFirst);
    }

    // ============================================================
    // 이벤트 미발행 — event_publication 무변화
    // ============================================================

    @Test
    @DisplayName("deliver 커밋 후 event_publication 무변화 (배송완료 이벤트 없음, 020 회귀 확인)")
    void deliver_noEventPublication() {
        long userId = insertUser("deliver-noevent-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertShippingOrder(userId, variantId, "이벤트없음상품", 1, BigDecimal.valueOf(2000));

        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        shipToShipping(shipmentId);

        int beforeCount = countEventPublications();

        orderFulfillmentService.deliver(shipmentId);

        int afterCount = countEventPublications();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    // ============================================================
    // 단일 배송 주문 재-deliver → 멱등 200 (revision-2)
    // ============================================================

    @Test
    @DisplayName("단일 배송 주문 이미 delivered → 재-deliver 멱등 200, 주문/shipment 불변, 방어 가드 409 아님 (revision-2)")
    void deliver_singleShipment_orderAlreadyDelivered_idempotent200() {
        long userId = insertUser("deliver-redelivery-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertShippingOrder(userId, variantId, "재배송상품", 1, BigDecimal.valueOf(4000));

        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        shipToShipping(shipmentId);

        // 최초 deliver → 주문 shipped → delivered rollup
        orderFulfillmentService.deliver(shipmentId);

        String orderStatusBeforeRedelivery = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusBeforeRedelivery).isEqualTo("delivered");

        // 재-deliver → 멱등 200 (방어 가드 409 아님)
        DeliverResponse reResult = orderFulfillmentService.deliver(shipmentId);

        assertThat(reResult.shipment().status()).isEqualTo("delivered");
        assertThat(reResult.orderDelivered()).isTrue();

        // 주문/shipment 불변
        String orderStatusAfterRedelivery = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id=?", String.class, orderId);
        assertThat(orderStatusAfterRedelivery).isEqualTo("delivered");
    }

    // ============================================================
    // 잘못된 전이 → 409
    // ============================================================

    @Test
    @DisplayName("preparing 배송 deliver → 409 OrderFulfillmentConflictException")
    void deliver_preparingShipment_409() {
        long userId = insertUser("deliver-prep-1@test.com");
        long variantId = insertVariant(5);
        long orderId = insertShippingOrder(userId, variantId, "preparing배송", 1, BigDecimal.valueOf(1000));

        var createResp = orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = createResp.shipmentId();
        // ship 하지 않음 → shipment는 preparing 상태

        assertThatThrownBy(() -> orderFulfillmentService.deliver(shipmentId))
                .isInstanceOf(OrderFulfillmentConflictException.class);

        // shipment 상태 불변
        String shipmentStatus = jdbc.queryForObject(
                "SELECT status FROM shipments WHERE id=?", String.class, shipmentId);
        assertThat(shipmentStatus).isEqualTo("preparing");
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
        String sku = "DELIVER-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('배송완료테스트상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertShippingOrder(long userId, long variantId, String productName,
                                     int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-DELIVER-" + System.nanoTime();
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
        String orderNumber = "ORD-DELIVER-2I-" + System.nanoTime();
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

    /** preparing → shipping 전이 (직접 DB UPDATE로 shipping 상태 세팅 — ship()은 SPI 의존 있음) */
    private void shipToShipping(long shipmentId) {
        jdbc.update("UPDATE shipments SET status='shipping', carrier='CJ대한통운', "
                + "tracking_number='TRK-DELIVER-' || id, shipped_at=now() WHERE id=?", shipmentId);
        // orders.status도 shipping으로 세팅
        jdbc.update("UPDATE orders SET status='shipping' WHERE id = "
                + "(SELECT order_id FROM shipments WHERE id=?)", shipmentId);
    }

    private List<Long> getOrderItemIds(long orderId) {
        return jdbc.queryForList(
                "SELECT id FROM order_items WHERE order_id=? ORDER BY id", Long.class, orderId);
    }

    private int countEventPublications() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class);
        return count != null ? count : 0;
    }
}
