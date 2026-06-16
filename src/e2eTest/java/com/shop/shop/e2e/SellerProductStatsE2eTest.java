package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
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
 * Task 040 판매자 상품 현황 페이지 E2E.
 *
 * <p>검증: ① 완료 주문(paid·delivered)의 판매수량·매출이 상품 행에 정확 집계,
 * ② 취소·환불·pending 주문은 집계 제외, ③ 총재고 표기, ④ 타 판매자 상품 비노출(IDOR).
 */
class SellerProductStatsE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private int orderSeq = 0;

    @Test
    @DisplayName("(1) 현황: 완료 주문만 집계(취소·환불·pending 제외) + 총재고 + 타 판매자 상품 비노출")
    void statsPage_aggregatesCompletedOnly_excludesCancelledRefunded_andHidesOtherSeller() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");

        String productName;
        String otherProductName;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
            long productId = insertProduct(conn, sellerId);
            productName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productId);
            long variantId = insertVariant(conn, productId, "E2E-STAT-SKU-" + System.nanoTime(), 10000, 7, true);

            // 집계 대상: paid(qty 2, 20000) + delivered(qty 3, 30000) = 판매수량 5, 매출 50,000
            seedSale(conn, sellerId, variantId, "paid", 2, 20000);
            seedSale(conn, sellerId, variantId, "delivered", 3, 30000);
            // 집계 제외: cancelled / refunded / pending
            seedSale(conn, sellerId, variantId, "cancelled", 5, 50000);
            seedSale(conn, sellerId, variantId, "refunded", 1, 10000);
            seedSale(conn, sellerId, variantId, "pending", 4, 40000);

            // 타 판매자 상품 + 완료 판매 (IDOR: 현재 판매자 현황에 안 보여야)
            String otherEmail = uniqueEmail();
            signup(otherEmail, PASSWORD);
            promoteRole(otherEmail, "SELLER");
            long otherSellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(otherEmail));
            long otherProductId = insertProduct(conn, otherSellerId);
            otherProductName = scalarStr(conn, "SELECT name FROM products WHERE id=" + otherProductId);
            long otherVariantId = insertVariant(conn, otherProductId, "E2E-OTHER-SKU-" + System.nanoTime(), 5000, 3, true);
            seedSale(conn, otherSellerId, otherVariantId, "paid", 9, 45000);
        }

        submitLogin(email, PASSWORD);
        page.navigate(BASE_URL + "/seller/products/stats");

        // 한계 주석 노출
        assertThat(page.getByText("삭제된 옵션의 과거 판매분은 제외").first()).isVisible();

        // 내 상품 행: 총재고 7 / 판매수량 5 / 매출 50,000 (취소·환불·pending 미포함)
        Locator row = page.locator("tr").filter(new Locator.FilterOptions().setHasText(productName));
        assertThat(row).isVisible();
        // 열 순서: 상품명(0) / 상태(1) / 총재고(2) / 판매수량(3) / 매출(4)
        assertThat(row.locator("td").nth(2)).hasText("7");
        assertThat(row.locator("td").nth(3)).hasText("5");
        assertThat(row.locator("td").nth(4)).containsText("50,000");

        // 타 판매자 상품은 안 보임 (IDOR)
        assertThat(page.getByText(otherProductName)).hasCount(0);
    }

    // ===== JDBC 헬퍼 =====

    /** 완료/미완료 주문 1건 + 항목 1건 시드(buyer=판매자 자신, 집계는 buyer 무관). */
    private void seedSale(Connection conn, long buyerId, long variantId, String status, int qty, int lineAmount) throws SQLException {
        long orderId = insertOrder(conn, buyerId, status, lineAmount);
        insertOrderItem(conn, orderId, variantId, qty, lineAmount);
    }

    private long insertOrder(Connection conn, long buyerId, String status, int amount) throws SQLException {
        String orderNumber = "E2E-ORD-" + System.nanoTime() + "-" + (orderSeq++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, final_amount) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, buyerId);
            ps.setString(2, orderNumber);
            ps.setString(3, status);
            ps.setInt(4, amount);
            ps.setInt(5, amount);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM orders WHERE order_number=" + q(orderNumber));
    }

    private void insertOrderItem(Connection conn, long orderId, long variantId, int qty, int lineAmount) throws SQLException {
        int unitPrice = lineAmount / qty;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, variantId);
            ps.setString(3, "E2E상품스냅샷");
            ps.setInt(4, unitPrice);
            ps.setInt(5, qty);
            ps.setInt(6, lineAmount);
            ps.executeUpdate();
        }
    }

    private long insertProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E현황상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task040");
            ps.setString(3, "ON_SALE");
            ps.setLong(4, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM products WHERE name=" + q(name) + " ORDER BY id DESC LIMIT 1");
    }

    private long insertVariant(Connection conn, long productId, String sku, int price, int stock, boolean active) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, productId); ps.setString(2, sku); ps.setInt(3, price); ps.setInt(4, stock); ps.setBoolean(5, active); ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + q(sku));
    }

    private void promoteRole(String email, String role) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role); ps.setString(2, email); ps.executeUpdate();
        }
    }

    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next(); return rs.getLong(1);
        }
    }

    private String scalarStr(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next(); return rs.getString(1);
        }
    }

    /** SQL 문자열 리터럴(작은따옴표 감싸기). */
    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
