package com.shop.shop.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 048 판매자 주문 조회 · 소유권 스코핑 E2E.
 *
 * <p>검증 대상:
 * <ol>
 *   <li>자기 소유 항목이 든 주문이 /seller/orders에 표시되고 상태 라벨이 한글로 렌더됨.</li>
 *   <li>배송 상태 라벨 — shipmentStatus='shipping' → '배송 중' (영문 raw 값 미노출).</li>
 *   <li>타 판매자 소유 항목만 든 주문은 표시 안 됨(소유권 스코핑 — IDOR 방지).</li>
 *   <li>비SELLER(CONSUMER) 접근 차단.</li>
 * </ol>
 *
 * <p>핵심 스키마: {@code order_items.owner_id} — 이 컬럼이 스코핑 키다.
 * JDBC 직접 시드이므로 createOrderTx 스냅샷 경로를 거치지 않아 {@code owner_id}를 <b>반드시 명시</b>한다.
 *
 * <p>실행: e2e-runner 담당(라이브 앱 필요). 본 클래스에서 {@code ./gradlew e2eTest} 금지.
 */
class SellerOrderE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private int orderSeq = 0;

    // =========================================================
    // 테스트 (1): 자기 소유 항목 주문 표시 + 상태 라벨 한글
    // =========================================================

    @Test
    @DisplayName("(1) SELLER: 자기 소유 항목이 든 주문이 /seller/orders에 표시 + 상태 라벨 한글")
    void sellerOrders_showsOwnItemsWithKoreanStatusLabel() throws Exception {
        // 판매자 계정 생성
        String sellerEmail = uniqueEmail();
        signup(sellerEmail, PASSWORD);
        promoteRole(sellerEmail, "SELLER");

        String productName;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerEmail));

            // 게시 상품 + variant
            long productId = insertPublishedProduct(conn, sellerId);
            productName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productId);
            long variantId = insertVariant(conn, productId, "E2E-SEL-ORD-SKU-" + System.nanoTime());

            // 소비자(구매자) — 구매자는 별도 user(판매자가 자기 상품을 산 것처럼 시뮬레이션해도 무방)
            long consumerId = insertConsumer(conn, "e2e-consumer-" + System.nanoTime() + "@example.com");

            // 주문(status=paid) + order_item(owner_id=sellerId) — owner_id 명시 필수
            long orderId = insertOrder(conn, consumerId, "paid");
            insertOrderItem(conn, orderId, variantId, sellerId, productName);
        }

        // 판매자로 로그인 후 /seller/orders 접속
        page.navigate("/login");
        submitLogin(sellerEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 주문 블록이 최소 1개 표시됨
        assertThat(page.locator(".seller-order-block").first()).isVisible();

        // 상품명이 테이블에 노출됨
        assertThat(page.getByText(productName).first()).isVisible();

        // 주문 상태 라벨이 한글("결제 완료") — 영문 "paid" 미노출
        assertThat(page.locator(".badge-order-status").first()).hasText("결제 완료");
        assertThat(page.getByText("paid")).hasCount(0);
    }

    // =========================================================
    // 테스트 (2): 배송 상태 라벨 — shipping → '배송 중'
    // =========================================================

    @Test
    @DisplayName("(2) SELLER: 배송 상태 라벨 — shipping → '배송 중' (영문 raw 미노출)")
    void sellerOrders_shippingStatusLabel_showsKorean() throws Exception {
        String sellerEmail = uniqueEmail();
        signup(sellerEmail, PASSWORD);
        promoteRole(sellerEmail, "SELLER");

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerEmail));

            long productId = insertPublishedProduct(conn, sellerId);
            String productName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productId);
            long variantId = insertVariant(conn, productId, "E2E-SHIP-SKU-" + System.nanoTime());

            long consumerId = insertConsumer(conn, "e2e-ship-consumer-" + System.nanoTime() + "@example.com");
            long orderId = insertOrder(conn, consumerId, "shipping");
            long orderItemId = insertOrderItem(conn, orderId, variantId, sellerId, productName);

            // shipment(status=shipping) + shipment_item 연결
            // seller_id 컬럼: V4 스키마에 nullable 이음매로 존재 — 여기선 sellerId 명시
            insertShipmentWithItem(conn, orderId, orderItemId, sellerId, "shipping");
        }

        page.navigate("/login");
        submitLogin(sellerEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 배송 상태 셀이 "배송 중"으로 표시
        assertThat(page.locator(".badge-shipment-status").first()).hasText("배송 중");
        // 영문 원문 "shipping"은 DOM에 노출 안 됨
        assertThat(page.getByText("shipping")).hasCount(0);
    }

    // =========================================================
    // 테스트 (3): 타 판매자 상품만 든 주문은 안 보임(소유권 스코핑)
    // =========================================================

    @Test
    @DisplayName("(3) SELLER: 타 판매자 상품만 든 주문은 /seller/orders에 표시 안 됨(소유권 스코핑)")
    void sellerOrders_doesNotShowOtherSellersItems() throws Exception {
        // 판매자 A — 로그인 주체
        String sellerAEmail = uniqueEmail();
        signup(sellerAEmail, PASSWORD);
        promoteRole(sellerAEmail, "SELLER");

        // 판매자 B — 다른 판매자
        String sellerBEmail = uniqueEmail();
        signup(sellerBEmail, PASSWORD);
        promoteRole(sellerBEmail, "SELLER");

        String sellerBProductName;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerBId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerBEmail));

            // 판매자 B의 상품 + variant
            long productIdB = insertPublishedProduct(conn, sellerBId);
            sellerBProductName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productIdB);
            long variantIdB = insertVariant(conn, productIdB, "E2E-IDOR-B-SKU-" + System.nanoTime());

            long consumerId = insertConsumer(conn, "e2e-idor-consumer-" + System.nanoTime() + "@example.com");
            long orderId = insertOrder(conn, consumerId, "paid");

            // order_item의 owner_id = sellerBId (판매자 B 소유) — 판매자 A는 이 항목을 볼 수 없어야 함
            insertOrderItem(conn, orderId, variantIdB, sellerBId, sellerBProductName);
        }

        // 판매자 A로 로그인
        page.navigate("/login");
        submitLogin(sellerAEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 판매자 B의 상품명이 목록에 없거나, 주문 블록 자체가 없음
        // — 판매자 A 소유 항목이 0이므로 해당 주문 블록이 노출되지 않아야 함
        assertThat(page.getByText(sellerBProductName)).hasCount(0);
        // 빈 상태 메시지가 표시됨(판매자 A에게 조회 가능한 주문 없음)
        assertThat(page.locator(".order-empty")).isVisible();
    }

    // =========================================================
    // 테스트 (4): 비SELLER(CONSUMER) 접근 차단
    // =========================================================

    @Test
    @DisplayName("(4) 비SELLER(CONSUMER): /seller/orders 접근 차단 — seller-order-block 미노출")
    void nonSeller_cannotAccessSellerOrders() throws Exception {
        // CONSUMER 기본 권한(role 승격 없음)
        String consumerEmail = uniqueEmail();
        signup(consumerEmail, PASSWORD);

        page.navigate("/login");
        submitLogin(consumerEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 판매자 주문 목록 요소가 없음(403 redirect 또는 접근 거부 페이지)
        assertThat(page.locator(".seller-order-block")).hasCount(0);
        assertThat(page.locator(".stat-cards-grid")).hasCount(0);
    }

    // =========================================================
    // JDBC 시드 헬퍼
    // =========================================================

    private long insertConsumer(Connection conn, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'x', '구매자E2E', 'CONSUMER')")) {
            ps.setString(1, email);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
    }

    private long insertPublishedProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E-판매자주문-상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task048");
            ps.setLong(3, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM products WHERE name=" + q(name) + " ORDER BY id DESC LIMIT 1");
    }

    private long insertVariant(Connection conn, long productId, String sku) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, 10000, 10, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + q(sku));
    }

    private long insertOrder(Connection conn, long buyerId, String status) throws SQLException {
        String orderNumber = "E2E-SELLER-ORD-" + System.nanoTime() + "-" + (orderSeq++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, final_amount) VALUES (?, ?, ?, 10000, 10000)")) {
            ps.setLong(1, buyerId);
            ps.setString(2, orderNumber);
            ps.setString(3, status);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM orders WHERE order_number=" + q(orderNumber));
    }

    /**
     * order_item 시드 — {@code owner_id} 명시 필수(소유권 스코핑 핵심).
     * JDBC 직접 insert이므로 createOrderTx 스냅샷 경로를 거치지 않는다.
     *
     * @return 생성된 order_item.id
     */
    private long insertOrderItem(Connection conn, long orderId, long variantId,
                                 long ownerId, String productName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_items "
                + "(order_id, variant_id, product_name, unit_price, quantity, line_amount, owner_id) "
                + "VALUES (?, ?, ?, 10000, 1, 10000, ?)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, variantId);
            ps.setString(3, productName);
            ps.setLong(4, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM order_items WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");
    }

    /**
     * shipment(status) + shipment_item 시드.
     * seller_id는 V4 스키마의 nullable 이음매 컬럼 — 여기선 명시 설정.
     */
    private void insertShipmentWithItem(Connection conn, long orderId, long orderItemId,
                                        long sellerId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO shipments (order_id, seller_id, status, carrier, tracking_number, shipped_at) "
                + "VALUES (?, ?, ?, 'CJ대한통운', ?, now())")) {
            ps.setLong(1, orderId);
            ps.setLong(2, sellerId);
            ps.setString(3, status);
            ps.setString(4, "TRK-E2E-" + System.nanoTime());
            ps.executeUpdate();
        }
        long shipmentId = scalarLong(conn,
                "SELECT id FROM shipments WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO shipment_items (shipment_id, order_item_id) VALUES (?, ?)")) {
            ps.setLong(1, shipmentId);
            ps.setLong(2, orderItemId);
            ps.executeUpdate();
        }
    }

    private void promoteRole(String email, String role) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String scalarStr(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
