package com.shop.shop.order.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V11__shipments_seller_id_backfill.sql 백필 검증 (Testcontainers 통합 테스트).
 *
 * <p>Flyway가 V11을 적용한 후 5가지 케이스를 단언한다:
 * <ol>
 *   <li>순수 단일소유 — 전 항목 동일 owner_id, owner NULL 0건 → seller_id 채워짐</li>
 *   <li>혼합소유(X·Y) — COUNT(DISTINCT) > 1 → seller_id NULL 유지</li>
 *   <li>전항목 owner NULL — COUNT(*) != COUNT(owner_id) (모두 null) → seller_id NULL 유지</li>
 *   <li>X+ownerNULL 혼합 엣지 — X 1건 + NULL 1건 → seller_id NULL 유지 (plan §1.2 엣지)</li>
 *   <li>이미 seller_id 있는 배송(049 스탬프) — WHERE seller_id IS NULL 제외 → 불변</li>
 * </ol>
 *
 * <p>시드: JDBC 직접 삽입으로 V11 적용 전 레거시 상태를 재현한다.
 * V11은 Flyway auto-apply(spring.flyway.enabled=true)로 테스트 DB에 적용된다.
 *
 * <p>★ 주의: Flyway는 컨테이너 기동 시 1회 실행된다.
 * 백필 검증은 "V11이 적용된 상태에서 직접 삽입한 행"이 아니라
 * "V11 적용 전 상태를 시뮬레이션" 방식으로 확인한다 — 시드를 먼저 삽입하고,
 * V11 SQL을 직접 재실행해 결과를 단언한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"
})
class ShipmentSellerIdBackfillIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EnvelopeEncryptionService crypto;

    // V11 SQL (plan §1.2에서 그대로 복사)
    private static final String V11_SQL =
            "UPDATE shipments s " +
            "SET    seller_id = sub.owner_id " +
            "FROM ( " +
            "    SELECT si.shipment_id, MIN(oi.owner_id) AS owner_id " +
            "    FROM   shipment_items si " +
            "    JOIN   order_items oi ON si.order_item_id = oi.id " +
            "    GROUP  BY si.shipment_id " +
            "    HAVING COUNT(DISTINCT oi.owner_id) = 1 " +
            "       AND COUNT(*) = COUNT(oi.owner_id) " +
            ") sub " +
            "WHERE s.id = sub.shipment_id " +
            "  AND s.seller_id IS NULL";

    // ============================================================
    // §3.1-1 순수 단일소유 → seller_id 채워짐
    // ============================================================

    @Test
    @DisplayName("§3.1-1 순수 단일소유: 전 항목 동일 owner_id, owner NULL 0건 → seller_id 백필")
    void backfill_pureSingleOwner_sellerIdFilled() {
        long sellerA = insertUser("backfill-pure-a-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("backfill-pure-buyer-" + System.nanoTime() + "@test.com");

        long orderId = insertPaidOrderBase(buyerId);
        long itemId1 = insertOrderItem(orderId, sellerA, "상품1");
        long itemId2 = insertOrderItem(orderId, sellerA, "상품2");

        // 레거시 배송 (seller_id=NULL)
        long shipmentId = insertLegacyShipment(orderId);
        insertShipmentItem(shipmentId, itemId1);
        insertShipmentItem(shipmentId, itemId2);

        // V11 백필 재실행
        jdbc.update(V11_SQL);

        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, shipmentId);
        assertThat(storedSellerId).isEqualTo(sellerA);
    }

    // ============================================================
    // §3.1-2 혼합소유(X·Y) → NULL 유지
    // ============================================================

    @Test
    @DisplayName("§3.1-2 혼합소유(X·Y): COUNT(DISTINCT)>1 → seller_id NULL 유지")
    void backfill_mixedOwners_sellerIdRemainsNull() {
        long sellerA = insertUser("backfill-mix-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("backfill-mix-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("backfill-mix-buyer-" + System.nanoTime() + "@test.com");

        long orderId = insertPaidOrderBase(buyerId);
        long itemAId = insertOrderItem(orderId, sellerA, "A상품혼합");
        long itemBId = insertOrderItem(orderId, sellerB, "B상품혼합");

        long shipmentId = insertLegacyShipment(orderId);
        insertShipmentItem(shipmentId, itemAId);
        insertShipmentItem(shipmentId, itemBId);

        jdbc.update(V11_SQL);

        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, shipmentId);
        assertThat(storedSellerId).isNull();
    }

    // ============================================================
    // §3.1-3 전항목 owner NULL → NULL 유지
    // ============================================================

    @Test
    @DisplayName("§3.1-3 전항목 owner NULL: COUNT(*) != COUNT(owner_id) → seller_id NULL 유지")
    void backfill_allOwnerNull_sellerIdRemainsNull() {
        long buyerId = insertUser("backfill-allnull-buyer-" + System.nanoTime() + "@test.com");

        long orderId = insertPaidOrderBase(buyerId);
        long itemNullId = insertOrderItemNullOwner(orderId, "owner없는상품");

        long shipmentId = insertLegacyShipment(orderId);
        insertShipmentItem(shipmentId, itemNullId);

        jdbc.update(V11_SQL);

        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, shipmentId);
        assertThat(storedSellerId).isNull();
    }

    // ============================================================
    // §3.1-4 X+ownerNULL 혼합 엣지 → NULL 유지
    // ============================================================

    @Test
    @DisplayName("§3.1-4 X+ownerNULL 혼합 엣지: X 항목 + owner=NULL 항목 혼재 → seller_id NULL 유지")
    void backfill_xPlusNullOwner_sellerIdRemainsNull() {
        long sellerA = insertUser("backfill-edge-a-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("backfill-edge-buyer-" + System.nanoTime() + "@test.com");

        long orderId = insertPaidOrderBase(buyerId);
        long itemAId = insertOrderItem(orderId, sellerA, "A항목엣지");
        long itemNullId = insertOrderItemNullOwner(orderId, "NULL항목엣지");

        long shipmentId = insertLegacyShipment(orderId);
        insertShipmentItem(shipmentId, itemAId);
        insertShipmentItem(shipmentId, itemNullId);

        jdbc.update(V11_SQL);

        // ★ 엣지: X+NULL 혼합은 COUNT(DISTINCT)=1 이지만 COUNT(*)≠COUNT(owner_id) → NULL 유지
        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, shipmentId);
        assertThat(storedSellerId).isNull();
    }

    // ============================================================
    // §3.1-5 이미 seller_id 있는 배송 → 불변
    // ============================================================

    @Test
    @DisplayName("§3.1-5 이미 seller_id 있는 배송(049 스탬프): WHERE seller_id IS NULL 제외 → seller_id 불변")
    void backfill_existingSellerId_unchanged() {
        long sellerA = insertUser("backfill-existing-a-" + System.nanoTime() + "@test.com");
        long sellerB = insertUser("backfill-existing-b-" + System.nanoTime() + "@test.com");
        long buyerId = insertUser("backfill-existing-buyer-" + System.nanoTime() + "@test.com");

        long orderId = insertPaidOrderBase(buyerId);
        long itemAId = insertOrderItem(orderId, sellerA, "A기존상품");

        // 이미 sellerB의 seller_id로 스탬프된 배송 (049 패턴)
        long shipmentId = insertShipmentWithSeller(orderId, sellerB);
        insertShipmentItem(shipmentId, itemAId);

        jdbc.update(V11_SQL);

        // sellerB 불변 (sellerA로 바뀌지 않아야 함)
        Long storedSellerId = jdbc.queryForObject(
                "SELECT seller_id FROM shipments WHERE id=?", Long.class, shipmentId);
        assertThat(storedSellerId).isEqualTo(sellerB);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "테스터", "SELLER", "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertPaidOrderBase(long buyerId) {
        String orderNumber = "ORD-BACKFILL-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                crypto.encrypt("수령인"), crypto.encrypt("010-0000-0000"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    private long insertOrderItem(long orderId, long ownerId, String productName) {
        String unique = productName + "-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO order_items(order_id, product_name, owner_id, unit_price, quantity, line_amount) " +
                    "VALUES(?,?,?,?,?,?)",
                orderId, unique, ownerId, amount, 1, amount);
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? AND product_name=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId, unique);
    }

    private long insertOrderItemNullOwner(long orderId, String productName) {
        String unique = productName + "-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO order_items(order_id, product_name, owner_id, unit_price, quantity, line_amount) " +
                    "VALUES(?,?,?,?,?,?)",
                orderId, unique, null, amount, 1, amount);
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? AND product_name=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId, unique);
    }

    private long insertLegacyShipment(long orderId) {
        // seller_id=NULL (레거시 admin 생성 패턴)
        jdbc.update("INSERT INTO shipments(order_id, seller_id, status) VALUES(?,?,?)",
                orderId, null, "preparing");
        return jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId);
    }

    private long insertShipmentWithSeller(long orderId, long sellerId) {
        jdbc.update("INSERT INTO shipments(order_id, seller_id, status) VALUES(?,?,?)",
                orderId, sellerId, "preparing");
        return jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId);
    }

    private void insertShipmentItem(long shipmentId, long orderItemId) {
        jdbc.update("INSERT INTO shipment_items(shipment_id, order_item_id) VALUES(?,?)",
                shipmentId, orderItemId);
    }
}
