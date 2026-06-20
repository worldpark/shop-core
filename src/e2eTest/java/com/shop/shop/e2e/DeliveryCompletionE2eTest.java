package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shop.shop.e2e.support.E2ePii;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 021 배송 완료(deliver) 브라우저 E2E.
 *
 * <p>관리자가 {@code /admin/orders}에서 {@code shipping} 배송의 "배송 완료" 버튼을 클릭하면
 * (실제 CSRF 토큰 포함 폼 POST → flash → redirect) 배송이 {@code delivered}로 전이되고
 * 주문이 rollup되는 전체 경로를 실제 브라우저 + 실제 앱 + 실제 Postgres로 검증한다.
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있고,
 * admin 계정(admin@example.com / Admin1234!)이 시드돼 있어야 한다(AdminAccountSeedTest).
 * shipping 상태 주문/배송은 본 테스트가 JDBC로 직접 시드한다(구매→결제→배송 전체 체인을
 * 브라우저로 몰지 않고 상태만 준비 — deliver 경로 검증에 집중).
 */
class DeliveryCompletionE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin1234!";

    @Test
    @DisplayName("관리자 배송 완료 버튼 클릭 → shipment delivered + 주문 rollup + 배송완료시각 표시")
    void adminDeliverShipment_throughBrowser() throws Exception {
        long shipmentId;
        long orderId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = insertConsumer(conn, "e2e-deliver-" + System.nanoTime() + "@example.com");
            long variantId = insertVariant(conn, 5);
            orderId = insertShippingOrder(conn, userId, variantId);
            long orderItemId = firstOrderItemId(conn, orderId);
            shipmentId = insertShippingShipment(conn, orderId, orderItemId);
        }

        // 1. 관리자 로그인
        page.navigate("/login");
        submitLogin(ADMIN_EMAIL, ADMIN_PASSWORD);

        // 2. 관리자 주문 목록 — 방금 시드한 shipping 배송의 "배송 완료" 폼이 노출돼야 한다
        page.navigate("/admin/orders");
        Locator deliverForm = page.locator(
                "form[action$='/admin/shipments/" + shipmentId + "/deliver']");
        assertThat(deliverForm).isVisible();
        assertThat(deliverForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료"))).isVisible();

        // 3. 배송 완료 클릭 (실제 CSRF 폼 POST → PRG redirect)
        deliverForm.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료")).click();

        // 4. flashSuccess + redirect(/admin/orders)
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/admin/orders.*"));
        assertThat(page.getByText("배송이 완료 처리되었습니다.")).isVisible();

        // 5. 같은 배송의 deliver 폼은 사라지고(이제 delivered), 배송완료시각이 표시된다
        assertThat(page.locator(
                "form[action$='/admin/shipments/" + shipmentId + "/deliver']")).hasCount(0);

        // 6. DB 교차검증 — shipment delivered + delivered_at 기록 + 단일배송 rollup으로 주문 delivered
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn, "SELECT status FROM shipments WHERE id=" + shipmentId, "delivered");
            assertNotNull(conn, "SELECT delivered_at FROM shipments WHERE id=" + shipmentId);
            assertScalar(conn, "SELECT status FROM orders WHERE id=" + orderId, "delivered");
        }
    }

    // =============================================================
    // JDBC 시드 헬퍼 (running Postgres 직접 연결)
    // =============================================================

    private long insertConsumer(Connection conn, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'x', ?, 'CONSUMER')")) {
            ps.setString(1, email);
            ps.setString(2, E2ePii.enc("배송완료E2E"));
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email='" + email + "'");
    }

    private long insertVariant(Connection conn, int stock) throws SQLException {
        String sku = "E2E-DELIVER-SKU-" + System.nanoTime();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO products (name, description, base_price, status) "
                    + "VALUES ('배송완료E2E상품', '설명', 5000, 'ON_SALE')");
        }
        long productId = scalarLong(conn, "SELECT id FROM products ORDER BY id DESC LIMIT 1");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, 5000, ?, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.setInt(3, stock);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku='" + sku + "'");
    }

    private long insertShippingOrder(Connection conn, long userId, long variantId) throws SQLException {
        String orderNumber = "ORD-E2E-DELIVER-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                        + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                        + "VALUES (?, ?, 'shipping', 5000, 0, 0, 5000, ?, ?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, orderNumber);
            ps.setString(3, E2ePii.enc("수령인"));
            ps.setString(4, E2ePii.enc("010-1234-5678"));
            ps.setString(5, E2ePii.enc("12345"));
            ps.setString(6, E2ePii.enc("서울시"));
            ps.executeUpdate();
        }
        long orderId = scalarLong(conn, "SELECT id FROM orders WHERE order_number='" + orderNumber + "'");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                        + "VALUES (?, ?, '배송완료E2E상품', 5000, 1, 5000)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, variantId);
            ps.executeUpdate();
        }
        return orderId;
    }

    private long firstOrderItemId(Connection conn, long orderId) throws SQLException {
        return scalarLong(conn, "SELECT id FROM order_items WHERE order_id=" + orderId + " ORDER BY id LIMIT 1");
    }

    /** shipping 상태 배송 + 배송 항목 시드(carrier/tracking/shipped_at 채움). */
    private long insertShippingShipment(Connection conn, long orderId, long orderItemId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO shipments (order_id, status, carrier, tracking_number, shipped_at) "
                        + "VALUES (?, 'shipping', 'CJ대한통운', ?, now())")) {
            ps.setLong(1, orderId);
            ps.setString(2, "TRK-E2E-" + System.nanoTime());
            ps.executeUpdate();
        }
        long shipmentId = scalarLong(conn, "SELECT id FROM shipments WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");
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

    private void assertScalar(Connection conn, String sql, String expected) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            String actual = rs.getString(1);
            if (!expected.equals(actual)) {
                throw new AssertionError("DB 교차검증 실패: [" + sql + "] expected=" + expected + " actual=" + actual);
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
}
