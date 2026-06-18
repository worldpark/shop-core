package com.shop.shop.order.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.order.spi.OrderExpiryReader;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderExpiryReader#findExpiredPendingOrderIds} 실 DB 동작 검증 (Testcontainers).
 *
 * <p>Acceptance 검증:
 * <ul>
 *   <li>(a) TTL 미만(now-5m, threshold=now-30m) pending 주문 → 결과에 미포함</li>
 *   <li>(b) TTL 초과(now-1h) pending 주문 → 결과에 포함</li>
 *   <li>(c) paid/cancelled 주문(created_at 과거여도) → 결과에서 제외</li>
 *   <li>(d) created_at asc, id asc 정렬 + limit(batchLimit) 적용 확인</li>
 * </ul>
 *
 * <p>created_at은 DB 소유(insertable=false)이므로 SQL로 과거 세팅(022/018 통합 테스트 방식 답습).
 * threshold는 호출자(스케줄러)가 주입하는 방식 그대로 검증 — DB now() 하드코딩 없음.
 *
 * <p>018/022 Testcontainers 프로파일 답습 (C12):
 * {@code @AutoConfigureTestDatabase(NONE)} + {@code @Testcontainers}(postgres:16.4-alpine + {@code @ServiceConnection})
 * + {@code @TestPropertySource}(exclude 리셋 + flyway enabled + ddl validate) + 외부화 enabled=false.
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
class OrderExpiryReaderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderExpiryReader orderExpiryReader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EnvelopeEncryptionService crypto;

    // ============================================================
    // (a) TTL 미만 pending → 결과에 미포함
    // ============================================================

    @Test
    @DisplayName("(a) TTL 미만 pending(now-5m, threshold=now-30m) → findExpiredPendingOrderIds 결과에 미포함")
    void findExpiredPendingOrderIds_ttlNotReached_notIncluded() {
        // given — created_at = now-5m (threshold=now-30m 미충족)
        long userId = insertUser("expiry-reader-a@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "TTL미만상품", 1, BigDecimal.valueOf(1000));
        setCreatedAtPast(orderId, 5);   // 5분 전

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);  // 30분 전 기준

        // when
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 100);

        // then — TTL 미만이므로 미포함
        assertThat(result).doesNotContain(orderId);
    }

    // ============================================================
    // (b) TTL 초과 pending → 결과에 포함
    // ============================================================

    @Test
    @DisplayName("(b) TTL 초과 pending(now-1h, threshold=now-30m) → findExpiredPendingOrderIds 결과에 포함")
    void findExpiredPendingOrderIds_ttlExceeded_included() {
        // given — created_at = now-1h (threshold=now-30m 초과)
        long userId = insertUser("expiry-reader-b@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "TTL초과상품", 1, BigDecimal.valueOf(2000));
        setCreatedAtPast(orderId, 60);  // 60분 전

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);  // 30분 전 기준

        // when
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 100);

        // then — TTL 초과이므로 포함
        assertThat(result).contains(orderId);
    }

    // ============================================================
    // (c) paid/cancelled 주문(created_at 과거여도) → 결과에서 제외
    // ============================================================

    @Test
    @DisplayName("(c) paid 주문(created_at 1h 전) → findExpiredPendingOrderIds 결과에서 제외")
    void findExpiredPendingOrderIds_paidOrder_excluded() {
        // given — paid 주문, created_at=1h 전 (threshold=30m → 충족)
        long userId = insertUser("expiry-reader-c1@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "paid상품", 1, BigDecimal.valueOf(3000));
        setCreatedAtPast(orderId, 60);
        jdbc.update("UPDATE orders SET status='paid' WHERE id=?", orderId);

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);

        // when
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 100);

        // then — status=paid이므로 제외
        assertThat(result).doesNotContain(orderId);
    }

    @Test
    @DisplayName("(c) cancelled 주문(created_at 1h 전) → findExpiredPendingOrderIds 결과에서 제외")
    void findExpiredPendingOrderIds_cancelledOrder_excluded() {
        // given — cancelled 주문, created_at=1h 전
        long userId = insertUser("expiry-reader-c2@test.com");
        long variantId = insertVariantWithStock(5);
        long orderId = insertPendingOrder(userId, variantId, "cancelled상품", 1, BigDecimal.valueOf(3000));
        setCreatedAtPast(orderId, 60);
        jdbc.update("UPDATE orders SET status='cancelled' WHERE id=?", orderId);

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);

        // when
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 100);

        // then — status=cancelled이므로 제외
        assertThat(result).doesNotContain(orderId);
    }

    // ============================================================
    // (d) created_at asc, id asc 정렬 + limit 적용
    // ============================================================

    @Test
    @DisplayName("(d) created_at asc, id asc 정렬 + limit 적용 — 오래된 것 먼저, 개수 제한")
    void findExpiredPendingOrderIds_sortAndLimit() throws InterruptedException {
        // given — 3건 삽입: 120분 전, 90분 전, 60분 전 (모두 threshold=30m 초과)
        long userId = insertUser("expiry-reader-d@test.com");
        long variantId = insertVariantWithStock(30);

        long orderId120 = insertPendingOrder(userId, variantId, "정렬상품120", 1, BigDecimal.valueOf(1000));
        setCreatedAtPast(orderId120, 120);

        // id asc 정렬 확인을 위해 created_at이 동일한 두 건 추가
        long orderId90 = insertPendingOrder(userId, variantId, "정렬상품90", 1, BigDecimal.valueOf(1000));
        setCreatedAtPast(orderId90, 90);

        long orderId60 = insertPendingOrder(userId, variantId, "정렬상품60", 1, BigDecimal.valueOf(1000));
        setCreatedAtPast(orderId60, 60);

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);  // 30분 전 기준 → 3건 모두 초과

        // when — limit=2
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 2);

        // then — 오래된 2건(120분, 90분) 포함, 60분은 제외 (limit=2)
        assertThat(result).hasSize(2);
        assertThat(result).contains(orderId120, orderId90);
        assertThat(result).doesNotContain(orderId60);

        // created_at asc 정렬: 120분 전이 먼저
        assertThat(result.get(0)).isEqualTo(orderId120);
        assertThat(result.get(1)).isEqualTo(orderId90);
    }

    @Test
    @DisplayName("(d) created_at 동일 시 id asc 정렬 확인")
    void findExpiredPendingOrderIds_samCreatedAt_sortByIdAsc() {
        // given — created_at을 동일한 과거 시각으로 세팅한 2건
        long userId = insertUser("expiry-reader-d2@test.com");
        long variantId = insertVariantWithStock(20);

        long orderIdFirst = insertPendingOrder(userId, variantId, "동일시각상품1", 1, BigDecimal.valueOf(1000));
        long orderIdSecond = insertPendingOrder(userId, variantId, "동일시각상품2", 1, BigDecimal.valueOf(1000));

        // 동일한 과거 시각으로 세팅
        Instant sameTime = Instant.now().minus(60, ChronoUnit.MINUTES);
        jdbc.update("UPDATE orders SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(sameTime), orderIdFirst);
        jdbc.update("UPDATE orders SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(sameTime), orderIdSecond);

        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);

        // when
        List<Long> result = orderExpiryReader.findExpiredPendingOrderIds(threshold, 100);

        // then — id asc 정렬: 먼저 삽입된(id 작은) 건이 앞에 위치
        int firstIdx = result.indexOf(orderIdFirst);
        int secondIdx = result.indexOf(orderIdSecond);
        assertThat(firstIdx).isGreaterThanOrEqualTo(0);
        assertThat(secondIdx).isGreaterThanOrEqualTo(0);
        assertThat(firstIdx).isLessThan(secondIdx);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'CONSUMER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertVariantWithStock(int stock) {
        String sku = "EXPIRY-READER-SKU-" + System.nanoTime();
        jdbc.update("INSERT INTO products (name, description, base_price, status) "
                + "VALUES ('만료Reader테스트상품', '설명', 5000, 'ON_SALE')");
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 5000, ?, true)", productId, sku, stock);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertPendingOrder(long userId, long variantId, String productName,
                                    int quantity, BigDecimal unitPrice) {
        String orderNumber = "ORD-EXPIRY-READER-" + System.nanoTime();
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, 'pending', ?, 0, 0, ?, ?, ?, ?, ?)",
                userId, orderNumber, lineAmount, lineAmount,
                crypto.encrypt("수령인"), crypto.encrypt("010-1234-5678"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        jdbc.update("INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, variantId, productName, unitPrice, quantity, lineAmount);
        return orderId;
    }

    private void setCreatedAtPast(long orderId, int minutesAgo) {
        Instant past = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        jdbc.update("UPDATE orders SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(past), orderId);
    }
}
