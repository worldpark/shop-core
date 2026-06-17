package com.shop.shop.e2e;

import com.microsoft.playwright.Response;
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
 * Task 046 관리자 대시보드 SSE 라이브 갱신 E2E.
 *
 * <p>기존 {@link AdminDashboardE2eTest}가 정적 렌더 + 비ADMIN 차단을 커버하므로
 * 본 클래스는 <b>SSE 라이브 갱신</b>(EventSource → DOM 갱신) 단 1가지 흐름만 검증한다.
 *
 * <p>핵심 단언: JDBC로 게시 상품을 추가(productSales 분모 +1)하면 <b>페이지 새로고침 없이</b>
 * {@code #product-sales-den} span이 갱신된 값으로 교체된다(SSE push → EventSource 수신 → JS DOM 갱신).
 *
 * <p>전제: 앱이 {@code shop.admin.dashboard.sse.enabled=true}(기본)이고
 * {@code shop.admin.dashboard.sse.interval}이 짧게(예: {@code PT5S} 이하) 기동돼야 단언
 * 타임아웃(15초) 내에 push가 도달한다. 별도 e2e-runner가 환경변수로 조정한다.
 */
class AdminDashboardSseE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    // SSE push를 기다리는 단언 타임아웃(ms). 기본 interval 10s + 여유 5s = 15s.
    private static final int SSE_ASSERT_TIMEOUT_MS = 15_000;

    @Test
    @DisplayName("ADMIN: 데이터 변경이 새로고침 없이 대시보드에 반영(SSE push)")
    void adminDashboard_sseUpdatesProductSalesDenominatorWithoutRefresh() throws Exception {
        // ── 1. ADMIN 계정 생성 + 권한 부여 ──
        String adminEmail = uniqueEmail();
        signup(adminEmail, PASSWORD);
        promoteRole(adminEmail, "ADMIN");

        // ── 2. 초기 시드: 분모 > 0 이 되도록 게시 상품 1개 + 주문 1개(환불율 분모) ──
        long adminId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            adminId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(adminEmail));
            insertPublishedProduct(conn, adminId);   // productSales 분모: ON_SALE 상품
            insertOrder(conn, adminId, "paid");       // refundRate 분모: 전체 주문
        }

        // ── 3. 로그인 후 대시보드 진입 ──
        //    SSE 연결이 수립될 때까지 /admin/dashboard/stream 200 응답을 대기한다(점진 향상 확인).
        submitLogin(adminEmail, PASSWORD);

        // waitForResponse: navigate 전에 등록해야 인터셉트 가능
        Response sseResponse = page.waitForResponse(
                r -> r.url().contains("/admin/dashboard/stream"),
                () -> page.navigate(BASE_URL + "/admin/dashboard")
        );
        // SSE 스트림 연결이 200으로 열렸음을 확인(text/event-stream)
        assertThat(page.locator(".stat-cards-grid")).isVisible();
        // 연결 status가 200이거나 스트림이 열리면 양호
        // (일부 브라우저 드라이버는 스트리밍 응답을 완료 전에 200 반환)
        int sseStatus = sseResponse.status();
        if (sseStatus != 200) {
            throw new AssertionError("SSE 스트림 응답 코드 비정상: " + sseStatus);
        }

        // ── 4. 현재 #product-sales-den 값 읽기 ──
        String denBefore = page.locator("#product-sales-den").textContent();
        long denBeforeValue = Long.parseLong(denBefore.trim());

        // ── 5. JDBC로 게시 상품 1개 추가 → productSales 분모 +1 ──
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            insertPublishedProduct(conn, adminId);
        }

        // ── 6. 새로고침 없이 #product-sales-den이 증가한 값으로 갱신되길 폴링 단언 ──
        //    EventSource가 'stats' 이벤트를 수신 → updateMetric('product-sales', ...) → denEl.textContent 갱신.
        //    assertThat(Locator)는 내부적으로 자동 폴링하므로 별도 sleep 불필요.
        String expectedDen = String.valueOf(denBeforeValue + 1);
        assertThat(page.locator("#product-sales-den"))
                .hasText(expectedDen, new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions()
                        .setTimeout(SSE_ASSERT_TIMEOUT_MS));
    }

    // ===== JDBC 헬퍼 =====

    private void insertPublishedProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E-SSE-상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task046 SSE");
            ps.setLong(3, ownerId);
            ps.executeUpdate();
        }
    }

    private void insertOrder(Connection conn, long buyerId, String status) throws SQLException {
        String orderNumber = "E2E-SSE-ORD-" + System.nanoTime();
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
