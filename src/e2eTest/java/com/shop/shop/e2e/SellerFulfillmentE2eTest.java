package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 049 판매자 배송 이행(배송 생성→시작→완료) 브라우저 E2E.
 *
 * <p>검증 대상:
 * <ol>
 *   <li>자기 소유 주문에 대한 배송 생성→시작→완료 종단 해피패스 흐름.</li>
 *   <li>타 판매자 shipment 시작/완료 폼이 자신 화면에 미노출(소유권 스코핑).</li>
 * </ol>
 *
 * <p>핵심 스키마:
 * <ul>
 *   <li>{@code order_items.owner_id} — 판매자 스코핑 키, JDBC 직접 시드 시 반드시 명시.</li>
 *   <li>{@code shipments.seller_id} — 배송 소유권 검사 키, 시드 시 반드시 명시.</li>
 * </ul>
 *
 * <p>PRG 패턴: 성공/실패 모두 {@code redirect:/seller/orders}.
 * Flash 키: {@code flashSuccess} / {@code flashError} (fragments/messages.html 렌더).
 *
 * <p>실행: e2e-runner 담당(라이브 앱 필요). {@code ./gradlew e2eTest} 금지.
 */
class SellerFulfillmentE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private int orderSeq = 0;

    // =========================================================
    // 테스트 (1): 자기 소유 주문 배송 생성→시작→완료 종단 해피패스
    // =========================================================

    @Test
    @DisplayName("(1) SELLER: 자기 주문 배송 생성→시작→완료 종단 — DB 교차검증 포함")
    void seller_createShipment_ship_deliver_happyPath() throws Exception {
        // 판매자 계정 생성
        String sellerEmail = uniqueEmail();
        signup(sellerEmail, PASSWORD);
        promoteRole(sellerEmail, "SELLER");

        long sellerId;
        long orderId;
        String productName;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            sellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerEmail));

            // 게시 상품 + variant
            long productId = insertPublishedProduct(conn, sellerId);
            productName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productId);
            long variantId = insertVariant(conn, productId, "E2E-FULFILL-SKU-" + System.nanoTime());

            // 소비자(구매자)
            long consumerId = insertConsumer(conn, "e2e-fulfill-buyer-" + System.nanoTime() + "@example.com");

            // 주문(status=paid) + order_item(owner_id=sellerId) — owner_id 명시 필수(소유권 스코핑 핵심)
            orderId = insertOrder(conn, consumerId, "paid");
            insertOrderItem(conn, orderId, variantId, sellerId, productName);
        }

        // 판매자 로그인
        page.navigate("/login");
        submitLogin(sellerEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // -------------------------------------------------------
        // 단계 1: 배송 생성 — "배송 생성" 폼이 노출돼야 한다
        // (orderStatus=paid AND unshippedOwnedItems >= 1 → 템플릿 노출 조건)
        // -------------------------------------------------------
        Locator createForm = page.locator(
                "form[action$='/seller/orders/" + orderId + "/shipments']");
        assertThat(createForm).isVisible();
        assertThat(createForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 생성"))).isVisible();

        // 배송 생성 클릭 (CSRF 폼 POST → PRG redirect)
        createForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 생성")).click();

        // PRG 후 /seller/orders로 redirect + flashSuccess
        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 생성되었습니다.")).isVisible();

        // DB 교차검증: shipment 생성 + seller_id == sellerId + status='preparing'
        long shipmentId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            shipmentId = scalarLong(conn,
                    "SELECT id FROM shipments WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + shipmentId, "preparing");
            assertScalar(conn,
                    "SELECT seller_id::text FROM shipments WHERE id=" + shipmentId,
                    String.valueOf(sellerId));
        }

        // UI: 배송 시작 폼이 노출됐는지 확인 (status=preparing → ship-start-form 노출 조건)
        Locator shipForm = page.locator(
                "form[action$='/seller/shipments/" + shipmentId + "/ship']");
        assertThat(shipForm).isVisible();

        // -------------------------------------------------------
        // 단계 2: 배송 시작 — 택배사·운송장 입력 후 submit
        // -------------------------------------------------------
        shipForm.locator("input[name='carrier']").fill("CJ대한통운");
        shipForm.locator("input[name='trackingNumber']").fill("TRK-E2E-" + System.nanoTime());
        shipForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 시작")).click();

        // PRG 후 /seller/orders + flashSuccess
        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 시작되었습니다.")).isVisible();

        // DB 교차검증: status='shipping', carrier·tracking_number 기록
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + shipmentId, "shipping");
            assertNotNull(conn,
                    "SELECT carrier FROM shipments WHERE id=" + shipmentId);
            assertNotNull(conn,
                    "SELECT tracking_number FROM shipments WHERE id=" + shipmentId);
            assertNotNull(conn,
                    "SELECT shipped_at FROM shipments WHERE id=" + shipmentId);
        }

        // UI: 배송 완료 폼이 노출됐는지 확인 (status=shipping → deliver-form 노출 조건)
        Locator deliverForm = page.locator(
                "form[action$='/seller/shipments/" + shipmentId + "/deliver']");
        assertThat(deliverForm).isVisible();
        assertThat(deliverForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료"))).isVisible();

        // -------------------------------------------------------
        // 단계 3: 배송 완료 — 버튼 클릭
        // -------------------------------------------------------
        deliverForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료")).click();

        // PRG 후 /seller/orders + flashSuccess
        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 완료 처리되었습니다.")).isVisible();

        // DB 교차검증: shipment delivered + delivered_at + 단일배송 rollup → order delivered
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + shipmentId, "delivered");
            assertNotNull(conn,
                    "SELECT delivered_at FROM shipments WHERE id=" + shipmentId);
            assertScalar(conn,
                    "SELECT status FROM orders WHERE id=" + orderId, "delivered");
        }

        // UI: 배송 완료 폼이 사라지고(이제 delivered) 배송 완료 상태 표시
        assertThat(page.locator(
                "form[action$='/seller/shipments/" + shipmentId + "/deliver']")).hasCount(0);
    }

    // =========================================================
    // 테스트 (2): 타 판매자 shipment는 시작/완료 폼 미노출(소유권)
    // =========================================================

    @Test
    @DisplayName("(2) SELLER: 타 판매자 shipment 시작·완료 폼이 자신 화면에 미노출(소유권 스코핑)")
    void sellerA_cannotSeeSellerB_shipmentForms() throws Exception {
        // 판매자 A — 로그인 주체
        String sellerAEmail = uniqueEmail();
        signup(sellerAEmail, PASSWORD);
        promoteRole(sellerAEmail, "SELLER");

        // 판매자 B — 타 판매자
        String sellerBEmail = uniqueEmail();
        signup(sellerBEmail, PASSWORD);
        promoteRole(sellerBEmail, "SELLER");

        long shipmentId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerBId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerBEmail));

            // 판매자 B의 상품 + variant
            long productIdB = insertPublishedProduct(conn, sellerBId);
            String productNameB = scalarStr(conn, "SELECT name FROM products WHERE id=" + productIdB);
            long variantIdB = insertVariant(conn, productIdB, "E2E-IDOR-B-SKU-" + System.nanoTime());

            long consumerId = insertConsumer(conn,
                    "e2e-idor-buyer-" + System.nanoTime() + "@example.com");

            // 주문(status=shipping) + order_item(owner_id=sellerBId) — 판매자 B 소유
            long orderIdB = insertOrder(conn, consumerId, "shipping");
            long orderItemIdB = insertOrderItem(conn, orderIdB, variantIdB, sellerBId, productNameB);

            // 판매자 B 소유 shipment(status=shipping) — seller_id = sellerBId
            shipmentId = insertShipmentWithItem(conn, orderIdB, orderItemIdB, sellerBId, "shipping");
        }

        // 판매자 A로 로그인
        page.navigate("/login");
        submitLogin(sellerAEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 판매자 A 화면에 판매자 B의 shipment 시작/완료 폼이 없어야 한다
        // (sellerShipments는 해당 판매자 소유만 포함 → 판매자 B shipment는 템플릿에 미렌더)
        assertThat(page.locator(
                "form[action$='/seller/shipments/" + shipmentId + "/ship']")).hasCount(0);
        assertThat(page.locator(
                "form[action$='/seller/shipments/" + shipmentId + "/deliver']")).hasCount(0);
    }

    // =========================================================
    // JDBC 시드 헬퍼
    // =========================================================

    private void promoteRole(String email, String role) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    private long insertConsumer(Connection conn, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'x', '구매자E2E', 'CONSUMER')")) {
            ps.setString(1, email);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
    }

    private long insertPublishedProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E-배송이행-상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) "
                + "VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task049 배송 이행");
            ps.setLong(3, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn,
                "SELECT id FROM products WHERE name=" + q(name) + " ORDER BY id DESC LIMIT 1");
    }

    private long insertVariant(Connection conn, long productId, String sku) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 10000, 10, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + q(sku));
    }

    private long insertOrder(Connection conn, long buyerId, String status) throws SQLException {
        String orderNumber = "E2E-FULFILL-ORD-" + System.nanoTime() + "-" + (orderSeq++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders "
                + "(user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, ?, 10000, 0, 0, 10000, '수령인', '010-0000-0000', '12345', '서울시')")) {
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
        return scalarLong(conn,
                "SELECT id FROM order_items WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");
    }

    /**
     * shipment(status) + shipment_item 시드.
     * seller_id 반드시 명시(소유권 검사 키).
     *
     * @return 생성된 shipment.id
     */
    private long insertShipmentWithItem(Connection conn, long orderId, long orderItemId,
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
        return shipmentId;
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

    private void assertScalar(Connection conn, String sql, String expected) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            String actual = rs.getString(1);
            if (!expected.equals(actual)) {
                throw new AssertionError(
                        "DB 교차검증 실패: [" + sql + "] expected=" + expected + " actual=" + actual);
            }
        }
    }

    private void assertNotNull(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            if (rs.getString(1) == null) {
                throw new AssertionError("DB 교차검증 실패: [" + sql + "] 값이 null");
            }
        }
    }

    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
