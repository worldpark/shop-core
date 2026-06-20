package com.shop.shop.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Tracing;
import com.shop.shop.e2e.support.E2ePii;
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
 * Task 051 — 판매자 판매 현황 SSE 실시간 갱신 + per-seller 격리 E2E.
 *
 * <p>검증 시나리오:
 * <ol>
 *   <li><b>per-seller 격리</b>: 판매자 A와 B가 동시에 /seller/products/stats를 열었을 때,
 *       A 상품이 주문·결제되면 A 화면의 해당 행 {@code td.sales-qty}만 갱신되고
 *       B 화면의 B 상품 행은 갱신되지 않음을 확인한다(A 주문은 B SSE에 무영향).</li>
 *   <li><b>실시간 갱신</b>: 페이지 새로고침 없이 SSE push를 통해 DOM이 갱신된다.</li>
 * </ol>
 *
 * <p>셀렉터:
 * <ul>
 *   <li>행 식별: {@code tr[data-product-id="<productId>"]}</li>
 *   <li>판매수량 셀: {@code td.sales-qty} (행 내부)</li>
 *   <li>매출 셀: {@code td span.revenue} (행 내부)</li>
 *   <li>SSE 엔드포인트: {@code /seller/products/stats/stream} (event: stats)</li>
 * </ul>
 *
 * <p>SSE broadcast interval 기본값: PT10S. 단언 타임아웃: 15s(interval + 여유 5s).
 * Playwright {@code assertThat(locator).hasText(..., timeout)} 내부 폴링으로 flaky 최소화.
 *
 * <p>멀티 컨텍스트: 판매자 A용 기본 컨텍스트({@code context}/{@code page}),
 * 판매자 B용 별도 컨텍스트({@code contextB}/{@code pageB}).
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있고,
 * {@code shop.seller.sales.sse.enabled=true}(기본) + interval이 PT10S 이하로 기동돼 있어야 한다.
 * 실행: e2e-runner 담당. {@code ./gradlew e2eTest} 직접 실행 금지.
 */
class SellerSalesStatsSseE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    /**
     * SSE broadcast tick(PT10S) + 여유 5s = 15s.
     * e2e-runner가 interval을 짧게(예: PT5S) 설정하면 더 빨리 통과한다.
     */
    private static final int SSE_ASSERT_TIMEOUT_MS = 15_000;

    private int orderSeq = 0;

    // =========================================================================
    // 판매자 B 전용 컨텍스트 (per-seller 격리 검증)
    // =========================================================================

    private BrowserContext contextB;
    private Page pageB;

    /**
     * 판매자 B용 별도 브라우저 컨텍스트를 열고 {@code pageB}를 초기화한다.
     * 판매자 A의 {@code context}/{@code page}와 세션이 완전히 분리된다.
     */
    private void openContextB() {
        Browser browser = context.browser();
        contextB = browser.newContext(new Browser.NewContextOptions().setBaseURL(BASE_URL));
        contextB.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true).setSnapshots(true).setSources(true));
        pageB = contextB.newPage();
    }

    private void closeContextB() {
        if (contextB != null) {
            contextB.close();
            contextB = null;
            pageB = null;
        }
    }

    // =========================================================================
    // 시나리오: per-seller 격리 + 실시간 갱신
    // =========================================================================

    @Test
    @DisplayName("SSE per-seller 격리: A 상품 주문 결제 → A 화면만 실시간 갱신, B 화면 불변")
    void sellerSalesStatsSse_perSellerIsolation_aOrderUpdatesAOnly() throws Exception {
        // ── 1. 판매자 A·B + 구매자 계정 생성 및 역할 승격 ──
        String sellerAEmail = uniqueEmail();
        signup(sellerAEmail, PASSWORD);
        promoteRole(sellerAEmail, "SELLER");

        String sellerBEmail = uniqueEmail();
        signup(sellerBEmail, PASSWORD);
        promoteRole(sellerBEmail, "SELLER");

        long productAId;
        long productBId;
        long variantAId;
        long buyerId;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerAUserId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerAEmail));
            long sellerBUserId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerBEmail));

            // 판매자 A: ON_SALE 상품 + active variant + 재고
            productAId = insertPublishedProduct(conn, sellerAUserId, "E2E-051-상품A");
            variantAId = insertVariant(conn, productAId, "E2E-051-SKU-A-" + System.nanoTime());

            // 판매자 B: ON_SALE 상품 + active variant + 재고
            // B variant는 SSE registry에 B의 variantId→productId 매핑이 캐시되도록 등록 (B 행 표시 전제)
            productBId = insertPublishedProduct(conn, sellerBUserId, "E2E-051-상품B");
            insertVariant(conn, productBId, "E2E-051-SKU-B-" + System.nanoTime());

            // 구매자 계정 생성
            buyerId = insertConsumer(conn, "e2e-051-buyer-" + System.nanoTime() + "@example.com");
        }

        // ── 2. 판매자 A: /seller/products/stats 열기 + SSE 연결 수립 ──
        page.navigate("/login");
        submitLogin(sellerAEmail, PASSWORD);

        // SSE 스트림 연결 200 확인 후 통계 페이지 진입
        Response sseResponseA = page.waitForResponse(
                r -> r.url().contains("/seller/products/stats/stream"),
                () -> page.navigate(BASE_URL + "/seller/products/stats")
        );
        if (sseResponseA.status() != 200) {
            throw new AssertionError("판매자 A SSE 스트림 응답 코드 비정상: " + sseResponseA.status());
        }

        // 판매자 A 상품 행 확인 + 초기 판매수량 = 0
        String rowSelectorA = "tr[data-product-id='" + productAId + "']";
        assertThat(page.locator(rowSelectorA)).isVisible();
        assertThat(page.locator(rowSelectorA + " td.sales-qty")).hasText("0");

        // ── 3. 판매자 B: 별도 컨텍스트로 /seller/products/stats 열기 + SSE 연결 수립 ──
        openContextB();
        try {
            pageB.navigate("/login");
            // pageB는 별도 컨텍스트 — AbstractE2eTest.submitLogin 은 this.page 기준이므로 직접 입력
            pageB.getByLabel("아이디").fill(sellerBEmail);
            pageB.getByLabel("비밀번호").fill(PASSWORD);
            pageB.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("로그인")).click();

            Response sseResponseB = pageB.waitForResponse(
                    r -> r.url().contains("/seller/products/stats/stream"),
                    () -> pageB.navigate(BASE_URL + "/seller/products/stats")
            );
            if (sseResponseB.status() != 200) {
                throw new AssertionError("판매자 B SSE 스트림 응답 코드 비정상: " + sseResponseB.status());
            }

            // 판매자 B 상품 행 확인 + 초기 판매수량 = 0
            String rowSelectorB = "tr[data-product-id='" + productBId + "']";
            assertThat(pageB.locator(rowSelectorB)).isVisible();
            assertThat(pageB.locator(rowSelectorB + " td.sales-qty")).hasText("0");

            // ── 4. 구매자가 A 상품을 주문·결제 완료(paid) ──
            // JDBC 직접 시드: owner_id = 판매자A userId (집계는 order.status 기준, owner_id는 소유권 스코핑 키)
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                long sellerAUserId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerAEmail));
                long orderId = insertOrder(conn, buyerId, "paid");
                // order_item.owner_id = sellerAUserId (판매자 A 소유)
                insertOrderItem(conn, orderId, variantAId, sellerAUserId, "E2E-051-A상품스냅샷", 2);
            }

            // ── 5. 다음 SSE broadcast tick 대기 → A 화면 판매수량 갱신 확인 (새로고침 없이) ──
            // broadcaster가 tick마다 aggregateByVariantIds를 호출해 SSE push,
            // 클라이언트 EventSource 'stats' 이벤트 수신 → JS가 tr[data-product-id] 행 패치.
            // assertThat 내부 폴링으로 최대 SSE_ASSERT_TIMEOUT_MS(15s) 대기.
            assertThat(page.locator(rowSelectorA + " td.sales-qty"))
                    .hasText("2",
                            new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions()
                                    .setTimeout(SSE_ASSERT_TIMEOUT_MS));

            // ── 6. 격리 확인: B 화면 B 상품 행은 갱신되지 않음(A 주문은 B SSE에 무영향) ──
            // B 상품 행 판매수량이 여전히 0 — SSE_ASSERT_TIMEOUT_MS 경과 후에도 불변임을 확인.
            // Playwright hasText는 현재 상태를 단언하는 것이므로,
            // A 갱신이 완료된 시점(5단계 통과 후) B는 아직 0임을 즉시 확인.
            assertThat(pageB.locator(rowSelectorB + " td.sales-qty")).hasText("0");

            // B 상품이 A 페이지에 노출되지 않는지도 확인 (per-seller 데이터 격리)
            assertThat(page.locator("tr[data-product-id='" + productBId + "']")).hasCount(0);

        } finally {
            closeContextB();
        }
    }

    // =========================================================================
    // 보조 단언: 비SELLER 접근 차단
    // =========================================================================

    @Test
    @DisplayName("비SELLER(CONSUMER): /seller/products/stats/stream 접근 차단")
    void nonSeller_cannotAccessSseStream() throws Exception {
        String consumerEmail = uniqueEmail();
        signup(consumerEmail, PASSWORD);
        // role 승격 없음 → CONSUMER 기본

        page.navigate("/login");
        submitLogin(consumerEmail, PASSWORD);

        // SSE 엔드포인트 직접 접근 → 403 또는 login redirect(비인증 redirect와 구분)
        Response sseResponse = page.waitForResponse(
                r -> r.url().contains("/seller/products/stats/stream")
                        || r.url().contains("/login")
                        || r.url().contains("/access-denied"),
                () -> page.navigate(BASE_URL + "/seller/products/stats/stream")
        );

        // /seller/** hasRole(SELLER) 가드: 403 또는 login redirect
        int status = sseResponse.status();
        boolean isAccessDenied = (status == 403)
                || page.url().contains("/login")
                || page.url().contains("/access-denied");
        if (!isAccessDenied) {
            throw new AssertionError(
                    "비SELLER가 SSE 스트림에 접근 가능: status=" + status + " url=" + page.url());
        }
    }

    // =========================================================================
    // JDBC 시드 헬퍼
    // =========================================================================

    private void promoteRole(String email, String role) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    /**
     * ON_SALE 상품 시드. namePrefix + nanoTime으로 유일성 보장.
     *
     * @return 생성된 product.id
     */
    private long insertPublishedProduct(Connection conn, long ownerId, String namePrefix) throws SQLException {
        String name = namePrefix + "-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task051 SSE");
            ps.setLong(3, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn,
                "SELECT id FROM products WHERE name=" + q(name) + " ORDER BY id DESC LIMIT 1");
    }

    /**
     * active variant + 재고 10 시드.
     *
     * @return 생성된 product_variants.id
     */
    private long insertVariant(Connection conn, long productId, String sku) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, 10000, 10, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + q(sku));
    }

    /**
     * CONSUMER 역할 사용자 시드 (구매자).
     *
     * @return 생성된 users.id
     */
    private long insertConsumer(Connection conn, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'x', ?, 'CONSUMER')")) {
            ps.setString(1, email);
            ps.setString(2, E2ePii.enc("구매자E2E051"));
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
    }

    /**
     * 주문 시드 (paid 상태 — 판매 인정 상태).
     *
     * @return 생성된 orders.id
     */
    private long insertOrder(Connection conn, long buyerId, String status) throws SQLException {
        String orderNumber = "E2E-051-ORD-" + System.nanoTime() + "-" + (orderSeq++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, final_amount) VALUES (?, ?, ?, 20000, 20000)")) {
            ps.setLong(1, buyerId);
            ps.setString(2, orderNumber);
            ps.setString(3, status);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM orders WHERE order_number=" + q(orderNumber));
    }

    /**
     * order_item 시드.
     *
     * <p>{@code owner_id} 명시 필수 — SellerSalesStatsPort.aggregateByVariantIds는 variantId 기준 집계이므로
     * owner_id는 소유권 스코핑(broadcaster가 이메일→variantId 매핑 캐시 기준으로 분배)을 위한 스냅샷이다.
     * qty × unitPrice(10000) = lineAmount.
     *
     * @param ownerId     판매자 users.id (상품 소유자)
     * @param qty         주문 수량 (salesQty 집계에 반영됨)
     */
    private void insertOrderItem(Connection conn, long orderId, long variantId,
                                 long ownerId, String productNameSnapshot, int qty) throws SQLException {
        int lineAmount = 10000 * qty;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_items "
                + "(order_id, variant_id, product_name, unit_price, quantity, line_amount, owner_id) "
                + "VALUES (?, ?, ?, 10000, ?, ?, ?)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, variantId);
            ps.setString(3, productNameSnapshot);
            ps.setInt(4, qty);
            ps.setInt(5, lineAmount);
            ps.setLong(6, ownerId);
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // JDBC 유틸리티
    // =========================================================================

    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** SQL 문자열 리터럴(작은따옴표 감싸기). */
    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
