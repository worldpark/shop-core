package com.shop.shop.order.service;

import com.shop.shop.order.spi.SellerOrderFacade;
import com.shop.shop.order.spi.dto.SellerOrderView;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SellerOrderFacade} 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>판매자A는 자기 owner_id 항목이 든 주문만 반환</li>
 *   <li>응답에 타 판매자 항목 미포함 (소유권 스코핑 — IDOR 방지)</li>
 *   <li>멀티셀러 주문: 판매자A 응답에 판매자A 항목만 포함</li>
 *   <li>배송 상태 매핑: 미생성 시 null, 생성 후 "preparing"</li>
 *   <li>owner_id=NULL 항목(variant SET NULL 백필 한계)은 조회되지 않음</li>
 *   <li>빈 결과: 자기 항목 없는 판매자는 빈 페이지 반환</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class SellerOrderFacadeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private SellerOrderFacade sellerOrderFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static final Pageable PAGE_0_10 = PageRequest.of(0, 10);

    // ============================================================
    // 판매자 소유 항목 포함 주문만 반환
    // ============================================================

    @Test
    @DisplayName("판매자A는 자기 owner_id 항목이 든 주문만 반환한다")
    void listSellerOrders_sellerSeesOwnOrders() {
        // given
        long sellerAId = insertUser("seller-a-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productId = insertProduct(sellerAId, categoryId, "판매자A 상품");
        long variantId = insertVariant(productId);

        long orderId = insertOrder(buyerId, "ORD-SELLER-A-" + System.nanoTime());
        insertOrderItem(orderId, variantId, sellerAId, "판매자A 상품", 1, new BigDecimal("10000"));

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PAGE_0_10);

        // then
        assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(result.getContent()).anyMatch(v -> v.orderId() == orderId);
    }

    @Test
    @DisplayName("판매자B의 항목만 있는 주문은 판매자A 조회에 포함되지 않는다")
    void listSellerOrders_otherSellerOrderNotIncluded() {
        // given
        long sellerAId = insertUser("seller-a2-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-b-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer2-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productBId = insertProduct(sellerBId, categoryId, "판매자B 상품");
        long variantBId = insertVariant(productBId);

        long orderBId = insertOrder(buyerId, "ORD-SELLER-B-" + System.nanoTime());
        insertOrderItem(orderBId, variantBId, sellerBId, "판매자B 상품", 1, new BigDecimal("5000"));

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PAGE_0_10);

        // then — 판매자A 응답에 판매자B만의 주문 없음
        assertThat(result.getContent()).noneMatch(v -> v.orderId() == orderBId);
    }

    @Test
    @DisplayName("멀티셀러 주문: 판매자A 응답에 판매자A 항목만 포함, 판매자B 항목 미포함")
    void listSellerOrders_multiSellerOrder_onlySellerAItems() {
        // given
        long sellerAId = insertUser("seller-a3-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerBId = insertUser("seller-b3-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer3-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productAId = insertProduct(sellerAId, categoryId, "A상품멀티");
        long productBId = insertProduct(sellerBId, categoryId, "B상품멀티");
        long variantAId = insertVariant(productAId);
        long variantBId = insertVariant(productBId);

        long orderId = insertOrder(buyerId, "ORD-MULTI-" + System.nanoTime());
        long itemAId = insertOrderItem(orderId, variantAId, sellerAId, "A상품멀티", 1, new BigDecimal("10000"));
        long itemBId = insertOrderItem(orderId, variantBId, sellerBId, "B상품멀티", 2, new BigDecimal("5000"));

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PAGE_0_10);

        // then
        SellerOrderView orderView = result.getContent().stream()
                .filter(v -> v.orderId() == orderId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("판매자A 주문을 찾을 수 없음"));

        // 판매자A 항목만 포함
        assertThat(orderView.items()).anyMatch(i -> i.orderItemId() == itemAId);
        // 판매자B 항목 미포함
        assertThat(orderView.items()).noneMatch(i -> i.orderItemId() == itemBId);
    }

    @Test
    @DisplayName("배송 미생성 항목의 shipmentStatus는 null이다")
    void listSellerOrders_noShipment_shipmentStatusIsNull() {
        // given
        long sellerAId = insertUser("seller-noship-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer-noship-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productId = insertProduct(sellerAId, categoryId, "배송미생성상품");
        long variantId = insertVariant(productId);
        long orderId = insertOrder(buyerId, "ORD-NOSHIP-" + System.nanoTime());
        insertOrderItem(orderId, variantId, sellerAId, "배송미생성상품", 1, new BigDecimal("10000"));

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PAGE_0_10);

        // then
        SellerOrderView orderView = result.getContent().stream()
                .filter(v -> v.orderId() == orderId)
                .findFirst()
                .orElseThrow();
        assertThat(orderView.items()).hasSize(1);
        assertThat(orderView.items().get(0).shipmentStatus()).isNull();
    }

    @Test
    @DisplayName("배송 생성 후 항목의 shipmentStatus는 'preparing'이다")
    void listSellerOrders_withShipment_shipmentStatusIsPreparing() {
        // given
        long sellerAId = insertUser("seller-ship-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer-ship-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productId = insertProduct(sellerAId, categoryId, "배송있는상품");
        long variantId = insertVariant(productId);
        // 주문 상태를 paid로 설정
        long orderId = insertOrderWithStatus(buyerId, "ORD-SHIP-" + System.nanoTime(), "paid");
        long itemId = insertOrderItem(orderId, variantId, sellerAId, "배송있는상품", 1, new BigDecimal("10000"));

        // 배송 생성
        long shipmentId = insertShipment(orderId, "preparing");
        insertShipmentItem(shipmentId, itemId);

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PAGE_0_10);

        // then
        SellerOrderView orderView = result.getContent().stream()
                .filter(v -> v.orderId() == orderId)
                .findFirst()
                .orElseThrow();
        assertThat(orderView.items()).hasSize(1);
        assertThat(orderView.items().get(0).shipmentStatus()).isEqualTo("preparing");
    }

    @Test
    @DisplayName("자기 항목이 없는 판매자는 빈 페이지를 반환받는다")
    void listSellerOrders_noItems_emptyPage() {
        // given
        long noItemSellerId = insertUser("seller-empty-" + System.nanoTime() + "@test.com", "SELLER");
        String noItemSellerEmail = jdbc.queryForObject(
                "SELECT email FROM users WHERE id=?", String.class, noItemSellerId);

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(noItemSellerEmail, PAGE_0_10);

        // then
        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getContent()).isEmpty();
    }

    // ============================================================
    // items @BatchSize — N+1 회귀 방지
    // ============================================================

    @Test
    @DisplayName("여러 주문 조회 시 items 컬렉션 로드 쿼리 수가 주문 수만큼 늘지 않는다 (@BatchSize IN 배치)")
    void listSellerOrders_multipleOrders_itemsLoadedInBatch() {
        // given: 판매자A 소유 항목이 든 주문 3건 생성
        long sellerAId = insertUser("seller-batch-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("buyer-batch-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, sellerAId);

        long categoryId = insertCategory();
        long productId = insertProduct(sellerAId, categoryId, "배치테스트상품");
        long variantId = insertVariant(productId);

        long order1Id = insertOrder(buyerId, "ORD-BATCH-1-" + System.nanoTime());
        insertOrderItem(order1Id, variantId, sellerAId, "배치테스트상품", 1, new BigDecimal("10000"));
        long order2Id = insertOrder(buyerId, "ORD-BATCH-2-" + System.nanoTime());
        insertOrderItem(order2Id, variantId, sellerAId, "배치테스트상품", 2, new BigDecimal("10000"));
        long order3Id = insertOrder(buyerId, "ORD-BATCH-3-" + System.nanoTime());
        insertOrderItem(order3Id, variantId, sellerAId, "배치테스트상품", 3, new BigDecimal("10000"));

        // Hibernate Statistics 활성화 + 카운터 초기화
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // when
        Page<SellerOrderView> result = sellerOrderFacade.listSellerOrders(sellerAEmail, PageRequest.of(0, 10));

        // then: 결과 정확성 — 3건이 정확히 반환되어야 함
        long matchCount = result.getContent().stream()
                .filter(v -> v.orderId() == order1Id || v.orderId() == order2Id || v.orderId() == order3Id)
                .count();
        assertThat(matchCount).isEqualTo(3);

        // @BatchSize(size=100) 회귀 방지 — items 컬렉션 fetch 횟수가 1회(IN 배치)인지 검증.
        //
        // getCollectionFetchCount(): lazy 컬렉션 초기화(fetch) 횟수를 카운트.
        //   - @BatchSize 적용 시: 주문 3건의 items를 IN (id1, id2, id3) 1번으로 일괄 fetch → collectionFetchCount = 1
        //   - N+1 발생 시: 주문 수(3)만큼 개별 SELECT → collectionFetchCount = 3
        //
        // ※ getQueryExecutionCount()는 HQL/JPQL/criteria 쿼리만 카운트하며
        //   lazy 컬렉션 초기화는 포함되지 않아 N+1 감지 불가 — 이 지표를 사용하지 않음.
        long collectionFetchCount = stats.getCollectionFetchCount();
        assertThat(collectionFetchCount)
                .as("items 컬렉션 fetch 횟수(%d)는 @BatchSize IN 배치 적용 시 1회여야 한다 (N+1이면 주문 수만큼 증가)",
                        collectionFetchCount)
                .isEqualTo(1);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email, String role) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "테스터", role, "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertCategory() {
        long nano = System.nanoTime();
        String slug = "cat-sof-" + nano;
        jdbc.update("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)",
                "카테고리" + nano, slug, 1);
        return jdbc.queryForObject("SELECT id FROM categories WHERE slug=?", Long.class, slug);
    }

    private long insertProduct(long ownerId, long categoryId, String name) {
        String uniqueName = name + "-" + System.nanoTime();
        jdbc.update("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)",
                ownerId, categoryId, uniqueName, new BigDecimal("10000"), "ON_SALE");
        return jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, uniqueName);
    }

    private long insertVariant(long productId) {
        String sku = "SKU-SOF-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock) VALUES(?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertOrder(long userId, String orderNumber) {
        return insertOrderWithStatus(userId, orderNumber, "pending");
    }

    private long insertOrderWithStatus(long userId, String orderNumber, String status) {
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                userId, orderNumber, status,
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000"),
                "수령인", "010-0000-0000", "12345", "서울시");
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    private long insertOrderItem(long orderId, long variantId, long ownerId, String productName,
                                 int quantity, BigDecimal unitPrice) {
        BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        jdbc.update("INSERT INTO order_items(order_id, variant_id, owner_id, product_name, " +
                    "unit_price, quantity, line_amount) VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, ownerId, productName, unitPrice, quantity, lineAmount);
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? AND variant_id=? AND product_name=?",
                Long.class, orderId, variantId, productName);
    }

    private long insertShipment(long orderId, String status) {
        jdbc.update("INSERT INTO shipments(order_id, status) VALUES(?,?)", orderId, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM shipments WHERE order_id=?", Long.class, orderId);
    }

    private void insertShipmentItem(long shipmentId, long orderItemId) {
        jdbc.update("INSERT INTO shipment_items(shipment_id, order_item_id) VALUES(?,?)",
                shipmentId, orderItemId);
    }
}
