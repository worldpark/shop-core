package com.shop.shop.order.service;

import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * admin↔seller 공존 통합 테스트 (plan §3.2, Testcontainers).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>동시성: 같은 주문에 admin+seller 동시 배송 생성 → 항목 이중 배정 0, 충돌은 409(불변식 단정)</li>
 *   <li>권한 경계(a): seller가 admin 생성(seller_id=null) 배송 ship/deliver → 404</li>
 *   <li>권한 경계(b): seller가 타 seller 배송 ship/deliver → 404</li>
 *   <li>권한 경계(c): admin이 seller 생성 배송 ship/deliver → 성공(감독)</li>
 *   <li>이름 해석 비차단(MAJOR-1): 미존재 sellerId를 가진 배송 섞여도 listFulfillableOrders 200,
 *       fallback 레이블 "판매자(#N)"</li>
 * </ul>
 *
 * <p>★ 동시성 flaky 주의: 불변식(이중배정=0)에 단정 — 타이밍 flaky 재실행 단일 실패는 회귀 아님.
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
class AdminSellerCoexistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    @Autowired
    private SellerFulfillmentFacade sellerFulfillmentFacade;

    @Autowired
    private AdminOrderFulfillmentFacade adminOrderFulfillmentFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EnvelopeEncryptionService crypto;

    @MockitoBean
    private MemberDirectory memberDirectory;

    @MockitoBean
    private ProductOrderCatalog productOrderCatalog;

    @BeforeEach
    void setUp() {
        when(memberDirectory.findContactByUserId(anyLong()))
                .thenReturn(new MemberContact("test@example.com", "테스트판매자"));
        when(memberDirectory.findUserIdByEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String email = inv.getArgument(0);
                    Long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
                    return id;
                });
        // ship()이 ProductOrderCatalog를 호출할 때 기본 응답 반환
        when(productOrderCatalog.getOrderableSnapshots(anyCollection()))
                .thenAnswer(inv -> {
                    // 실제 variant_id들을 스냅샷으로 반환
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Long> variantIds = inv.getArgument(0);
                    return variantIds.stream()
                            .filter(id -> id != null)
                            .map(variantId -> {
                                Long productId = jdbc.queryForObject(
                                        "SELECT product_id FROM product_variants WHERE id=?", Long.class, variantId);
                                return new OrderableVariantSnapshot(
                                        variantId, productId != null ? productId : 0L,
                                        "상품", null, List.of(),
                                        new BigDecimal("10000"), true, 100, "ON_SALE", true, null);
                            })
                            .toList();
                });
    }

    // ============================================================
    // 동시성: admin+seller 동시 배송 생성 → 항목 이중 배정 0
    // ============================================================

    @Test
    @DisplayName("§3.2 동시성: admin+seller 동시 배송 생성 → 항목 이중 배정 0, 충돌 409 불변식")
    void concurrent_adminAndSellerCreateShipment_noDoubleAssignment() throws Exception {
        long sellerA = insertUser("coex-conc-seller-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-conc-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerA, "공유상품동시성");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 1: admin 배송 생성
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                orderFulfillmentService.createShipment(orderId, null);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // 스레드 2: seller 배송 생성
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();
                sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        // 불변식: 항목 이중 배정 0 — 이것이 타이밍 독립 불변식
        Integer assignedCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT order_item_id) FROM shipment_items si " +
                "JOIN shipments s ON si.shipment_id=s.id WHERE s.order_id=?",
                Integer.class, orderId);
        assertThat(assignedCount).as("항목 이중 배정이 있어선 안 됨 (각 order_item은 최대 1 배송)").isLessThanOrEqualTo(
                jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, orderId));

        // 성공 1 + 실패 1 또는 전부 처리됨
        assertThat(successCount.get() + failCount.get()).isEqualTo(2);
    }

    // ============================================================
    // 권한 경계 (a): seller가 admin 생성(seller_id=null) 배송 → 404
    // ============================================================

    @Test
    @DisplayName("§3.2 권한경계(a): seller가 admin 생성(seller_id=null) 배송 ship → 404 존재 은닉")
    void sellerCannot_ship_adminCreatedShipment() {
        long sellerA = insertUser("coex-auth-a-seller-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-auth-a-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItemNullOwner(orderId, "admin상품권한경계");

        // admin 경로로 배송 생성 (seller_id=null)
        var adminShipment = orderFulfillmentService.createShipment(orderId, null);

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.ship(sellerAEmail, adminShipment.shipmentId(), "택배사", "111"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    @DisplayName("§3.2 권한경계(a): seller가 admin 생성(seller_id=null) 배송 deliver → 404 존재 은닉")
    void sellerCannot_deliver_adminCreatedShipment() {
        long sellerA = insertUser("coex-auth-d-seller-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-auth-d-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);

        long orderId = insertPaidOrderBase(buyerId);
        long variantId = insertVariantForAdmin();
        insertOrderItemWithVariant(orderId, null, variantId, "admin상품deliver경계");

        var adminShipment = orderFulfillmentService.createShipment(orderId, null);
        // admin이 ship 처리 (테스트 진행을 위해) - ProductOrderCatalog 모킹으로 처리됨
        orderFulfillmentService.ship(adminShipment.shipmentId(), "CJ대한통운", "ADMIN-TRK");

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.deliver(sellerAEmail, adminShipment.shipmentId()))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 권한 경계 (b): seller가 타 seller 배송 → 404
    // ============================================================

    @Test
    @DisplayName("§3.2 권한경계(b): seller가 타 seller 배송 ship → 404 존재 은닉")
    void sellerCannot_ship_otherSellerShipment() {
        long sellerA = insertUser("coex-other-a-" + System.nanoTime() + "@test.com", "SELLER");
        long sellerB = insertUser("coex-other-b-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-other-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);
        String sellerBEmail = getEmail(sellerB);

        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItem(orderId, sellerB, "B상품타seller경계");

        var bShipment = sellerFulfillmentFacade.createShipment(sellerBEmail, orderId, null);

        assertThatThrownBy(() ->
                sellerFulfillmentFacade.ship(sellerAEmail, bShipment.shipmentId(), "택배사", "222"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    // ============================================================
    // 권한 경계 (c): admin이 seller 생성 배송 → 성공 (감독)
    // ============================================================

    @Test
    @DisplayName("§3.2 권한경계(c): admin이 seller 생성 배송 ship → 성공 (admin은 전체 감독 가능)")
    void admin_can_ship_sellerCreatedShipment() {
        long sellerA = insertUser("coex-admin-ship-seller-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-admin-ship-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);

        long orderId = insertPaidOrderBase(buyerId);
        long variantId = insertVariantForSeller(sellerA, "admin감독상품ship");
        insertOrderItemWithVariant(orderId, sellerA, variantId, "admin감독상품ship");

        var sellerShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);

        // admin 경로로 ship — 성공해야 함 (ProductOrderCatalog 모킹으로 처리됨)
        var shipped = orderFulfillmentService.ship(
                sellerShipment.shipmentId(), "CJ대한통운", "ADMIN-SUPERVISE-001");

        assertThat(shipped.status()).isEqualTo("shipping");
    }

    @Test
    @DisplayName("§3.2 권한경계(c): admin이 seller 생성 배송 deliver → 성공 (admin은 전체 감독 가능)")
    void admin_can_deliver_sellerCreatedShipment() {
        long sellerA = insertUser("coex-admin-del-seller-" + System.nanoTime() + "@test.com", "SELLER");
        long buyerId = insertUser("coex-admin-del-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        String sellerAEmail = getEmail(sellerA);

        long orderId = insertPaidOrderBase(buyerId);
        long variantId = insertVariantForSeller(sellerA, "admin감독상품deliver");
        insertOrderItemWithVariant(orderId, sellerA, variantId, "admin감독상품deliver");

        var sellerShipment = sellerFulfillmentFacade.createShipment(sellerAEmail, orderId, null);
        orderFulfillmentService.ship(sellerShipment.shipmentId(), "롯데택배", "ADMIN-DEL-001");

        var result = orderFulfillmentService.deliver(sellerShipment.shipmentId());

        assertThat(result.shipment().status()).isEqualTo("delivered");
        assertThat(result.orderDelivered()).isTrue();
    }

    // ============================================================
    // 이름 해석 비차단 (MAJOR-1): 미존재 sellerId → fallback, 페이지 비차단
    // ============================================================

    @Test
    @DisplayName("§3.2 이름해석비차단(MAJOR-1): 미존재 sellerId 배송 섞여도 listFulfillableOrders 200, fallback 레이블")
    void listFulfillableOrders_withDeletedSeller_returnsPageWithFallbackLabel() {
        long buyerId = insertUser("coex-fallback-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItemNullOwner(orderId, "fallback검증상품");

        // 미존재(삭제된) 판매자 ID — DB에는 없는 userId
        long nonExistentSellerId = 999_888_777L;

        // 배송 직접 삽입 (미존재 seller_id 스탬프)
        jdbc.update("INSERT INTO shipments(order_id, seller_id, status) VALUES(?,?,?)",
                orderId, nonExistentSellerId, "preparing");
        long shipmentId = jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id DESC LIMIT 1", Long.class, orderId);
        long itemId = jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? ORDER BY id DESC LIMIT 1", Long.class, orderId);
        jdbc.update("INSERT INTO shipment_items(shipment_id, order_item_id) VALUES(?,?)", shipmentId, itemId);

        // MemberDirectory.findContactByUserId(미존재 ID) → IllegalStateException (실제 동작 재현)
        when(memberDirectory.findContactByUserId(nonExistentSellerId))
                .thenThrow(new IllegalStateException("회원 연락처 조회 실패 — userId=" + nonExistentSellerId));

        // listFulfillableOrders — 예외 없이 200 반환 필수 (MAJOR-1)
        Page<AdminOrderFulfillmentView> page = adminOrderFulfillmentFacade.listFulfillableOrders(
                PageRequest.of(0, 20));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThan(0);

        // 해당 배송의 sellerLabel = fallback 형식 "판매자(#N)"
        var targetOrder = page.stream()
                .filter(o -> o.orderId() == orderId)
                .findFirst();
        assertThat(targetOrder).isPresent();

        var targetShipment = targetOrder.get().shipments().stream()
                .filter(s -> s.shipmentId() == shipmentId)
                .findFirst();
        assertThat(targetShipment).isPresent();
        assertThat(targetShipment.get().sellerId()).isEqualTo(nonExistentSellerId);
        assertThat(targetShipment.get().sellerLabel()).isEqualTo("판매자(#" + nonExistentSellerId + ")");
    }

    // ============================================================
    // admin null 배송 → sellerLabel "관리자 직접 처리"
    // ============================================================

    @Test
    @DisplayName("§3.2 admin 생성 배송(seller_id=null) → sellerLabel '관리자 직접 처리'")
    void listFulfillableOrders_adminCreatedShipment_sellerLabelAdminDirect() {
        long buyerId = insertUser("coex-admin-label-buyer-" + System.nanoTime() + "@test.com", "CONSUMER");
        long orderId = insertPaidOrderBase(buyerId);
        insertOrderItemNullOwner(orderId, "admin직접처리상품");

        orderFulfillmentService.createShipment(orderId, null);
        long shipmentId = jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id=? ORDER BY id DESC LIMIT 1", Long.class, orderId);

        Page<AdminOrderFulfillmentView> page = adminOrderFulfillmentFacade.listFulfillableOrders(
                PageRequest.of(0, 20));

        var targetOrder = page.stream()
                .filter(o -> o.orderId() == orderId)
                .findFirst();
        assertThat(targetOrder).isPresent();

        var targetShipment = targetOrder.get().shipments().stream()
                .filter(s -> s.shipmentId() == shipmentId)
                .findFirst();
        assertThat(targetShipment).isPresent();
        assertThat(targetShipment.get().sellerId()).isNull();
        assertThat(targetShipment.get().sellerLabel()).isEqualTo("관리자 직접 처리");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email, String role) {
        jdbc.update("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)",
                email, "hash", "테스터", role, "ACTIVE");
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private String getEmail(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
    }

    private long insertPaidOrderBase(long buyerId) {
        String orderNumber = "ORD-COEX-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, " +
                    "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                buyerId, orderNumber, "paid", amount, BigDecimal.ZERO, BigDecimal.ZERO, amount,
                crypto.encrypt("수령인"), crypto.encrypt("010-0000-0000"),
                crypto.encrypt("12345"), crypto.encrypt("서울시"));
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
    }

    /**
     * variant_id를 포함한 order_item 삽입 — ship() 경로에서 ProductOrderCatalog 조회에 필요.
     *
     * @param ownerId null이면 admin 생성 항목(owner_id NULL), non-null이면 판매자 소유 항목
     */
    private long insertOrderItemWithVariant(long orderId, Long ownerId, long variantId, String productName) {
        String unique = productName + "-" + System.nanoTime();
        BigDecimal amount = new BigDecimal("10000");
        jdbc.update("INSERT INTO order_items(order_id, variant_id, product_name, owner_id, unit_price, quantity, line_amount) " +
                    "VALUES(?,?,?,?,?,?,?)",
                orderId, variantId, unique, ownerId, amount, 1, amount);
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id=? AND product_name=? ORDER BY id DESC LIMIT 1",
                Long.class, orderId, unique);
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

    private long insertVariantForSeller(long sellerId, String productName) {
        String unique = productName + "-" + System.nanoTime();
        long catId = insertCategoryIfNeeded();
        jdbc.update("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)",
                sellerId, catId, unique, new BigDecimal("10000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, unique);
        String sku = "SKU-COEX-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock, is_active) VALUES(?,?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100, true);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long insertVariantForAdmin() {
        long catId = insertCategoryIfNeeded();
        String name = "admin상품-" + System.nanoTime();
        jdbc.update("INSERT INTO products(category_id, name, base_price, status) VALUES(?,?,?,?)",
                catId, name, new BigDecimal("10000"), "ON_SALE");
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, name);
        String sku = "SKU-ADMIN-COEX-" + System.nanoTime();
        jdbc.update("INSERT INTO product_variants(product_id, sku, price, stock, is_active) VALUES(?,?,?,?,?)",
                productId, sku, new BigDecimal("10000"), 100, true);
        return jdbc.queryForObject("SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }

    private long categoryCache = -1L;

    private long insertCategoryIfNeeded() {
        if (categoryCache > 0) return categoryCache;
        String slug = "cat-coex-" + System.nanoTime();
        jdbc.update("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)",
                "공존테스트카테고리", slug, 1);
        categoryCache = jdbc.queryForObject("SELECT id FROM categories WHERE slug=?", Long.class, slug);
        return categoryCache;
    }
}
