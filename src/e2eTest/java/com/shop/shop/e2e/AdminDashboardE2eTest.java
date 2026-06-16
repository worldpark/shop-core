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
 * Task 043 관리자 통계 대시보드 E2E.
 *
 * <p>대시보드는 DB 전체를 집계하므로 정확한 비율은 단위 테스트(AdminDashboardAssemblerTest)가 검증한다.
 * 여기서는 전체 배선(컨트롤러→조합→3 facade→repo→DB→템플릿→인가)을 검증한다:
 * ① ADMIN이 /admin/dashboard에서 3지표 카드를 정상 렌더하고, 시드/로그인으로 세 분모가 모두 > 0이라
 *    세 비율이 모두 % 값으로 표시(데이터 없음 아님)된다 — 이용률 분모는 로그인한 admin(활성+최근접속, Task 042),
 *    판매율 분모는 시드한 게시 상품, 환불율 분모는 시드한 주문. ② 비ADMIN은 접근 차단.
 */
class AdminDashboardE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private int orderSeq = 0;

    @Test
    @DisplayName("(1) ADMIN: 대시보드 3지표 렌더 + 세 비율 모두 % 값(분모>0)")
    void adminDashboard_rendersThreeMetricsWithValues() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "ADMIN");

        // 분모 확보 시드: 게시 상품 1(판매율 분모) + 정상/환불 주문(환불율 분모)
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long adminId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
            insertPublishedProduct(conn, adminId);
            insertOrder(conn, adminId, "paid");
            insertOrder(conn, adminId, "refunded");
        }

        submitLogin(email, PASSWORD); // formLogin → InteractiveAuthenticationSuccessEvent → last_login 기록(Task 042)
        page.navigate(BASE_URL + "/admin/dashboard");

        // 헤더/기간
        assertThat(page.locator(".page-title")).hasText("통계 대시보드");
        assertThat(page.locator(".dashboard-period-badge")).hasText("최근 30일");

        // 3지표 카드
        assertThat(page.getByText("유저 이용률").first()).isVisible();
        assertThat(page.getByText("상품 판매율").first()).isVisible();
        assertThat(page.getByText("환불율").first()).isVisible();

        // 세 분모 모두 > 0 이라 세 비율이 모두 % 값(데이터 없음 0개)
        assertThat(page.locator(".stat-ratio-value")).hasCount(3);
        assertThat(page.locator(".stat-ratio-na")).hasCount(0);
    }

    @Test
    @DisplayName("(2) 비ADMIN(CONSUMER): 대시보드 접근 차단")
    void nonAdminCannotAccessDashboard() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD); // CONSUMER 기본 권한

        page.navigate(BASE_URL + "/admin/dashboard");

        // 대시보드가 렌더되지 않음(지표 카드 부재)
        assertThat(page.locator(".stat-cards-grid")).hasCount(0);
    }

    // ===== JDBC 헬퍼 =====

    private void insertPublishedProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E대시보드상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task043");
            ps.setLong(3, ownerId);
            ps.executeUpdate();
        }
    }

    private void insertOrder(Connection conn, long buyerId, String status) throws SQLException {
        String orderNumber = "E2E-DASH-ORD-" + System.nanoTime() + "-" + (orderSeq++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, final_amount) VALUES (?, ?, ?, 10000, 10000)")) {
            ps.setLong(1, buyerId);
            ps.setString(2, orderNumber);
            ps.setString(3, status);
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

    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
