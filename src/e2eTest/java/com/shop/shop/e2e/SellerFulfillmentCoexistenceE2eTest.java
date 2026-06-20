package com.shop.shop.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Tracing;
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
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 050 — 판매자/admin 배송 공존 정합 E2E (plan §3.3).
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li><b>시나리오 A</b> — 멀티셀러 부분 배송 롤업: 판매자 A·B가 한 주문에 각자 항목을 소유할 때,
 *       A만 deliver한 시점에는 order=shipping(부분), B까지 deliver하면 order=delivered(전체 롤업).</li>
 *   <li><b>시나리오 B</b> — admin 배송 표기 양분기: admin 화면({@code /admin/orders})에서
 *       판매자 생성 배송은 {@code sellerLabel}에 판매자 이름이, admin 직접 생성(seller_id=null) 배송은
 *       {@code "관리자 직접 처리"} 라벨이 {@code .shipment-seller} 클래스로 렌더된다.</li>
 * </ul>
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있고,
 * admin 계정(admin@example.com / Admin1234!)이 시드돼 있어야 한다(AdminAccountSeedTest).
 * 주문·배송 시드는 JDBC 직접 삽입 — 브라우저 구매 플로우를 우회하고 검증 경로에만 집중한다.
 *
 * <p>실행: e2e-runner 담당. {@code ./gradlew e2eTest} 직접 실행 금지(앱 기동 전제).
 */
class SellerFulfillmentCoexistenceE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin1234!";

    private int seqCounter = 0;

    // ==========================================================================
    // 시나리오 A — 멀티셀러 부분 배송 롤업
    // ==========================================================================

    /**
     * 판매자 A가 자기 항목 배송을 완료한 시점(판매자 B는 아직)에는 주문이 '배송 중'이고,
     * 판매자 B까지 완료하면 주문이 '배송 완료'로 롤업됨을 검증한다.
     *
     * <p>검증 항목:
     * <ol>
     *   <li>A ship → A deliver 후 DB: order.status = shipping (부분 완료, B 미완료)</li>
     *   <li>B ship → B deliver 후 DB: order.status = delivered (전체 롤업)</li>
     *   <li>판매자 A 화면(/seller/orders): 최종 주문 상태 라벨 '배송 완료' 렌더</li>
     * </ol>
     */
    @Test
    @DisplayName("(A) 멀티셀러 부분 배송 롤업 — A deliver 후 배송 중, A+B deliver 후 배송 완료")
    void multiSeller_partialDelivery_rollup() throws Exception {
        // 판매자 A — 브라우저 조작 주체
        String sellerAEmail = uniqueEmail();
        signup(sellerAEmail, PASSWORD);
        promoteRole(sellerAEmail, "SELLER");

        // 판매자 B — DB 시드만, 브라우저 별도 컨텍스트 불필요(배송 처리는 sellerB 로그인 후 수행)
        String sellerBEmail = uniqueEmail();
        signup(sellerBEmail, PASSWORD);
        promoteRole(sellerBEmail, "SELLER");

        long orderId;
        long sellerAShipmentId;
        long sellerBShipmentId;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerAId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerAEmail));
            long sellerBId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerBEmail));

            // 판매자 A 상품·variant
            long productAId = insertPublishedProduct(conn, sellerAId, "E2E-050A-상품A");
            String productAName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productAId);
            long variantAId = insertVariant(conn, productAId, "E2E-050A-SKU-A-" + System.nanoTime());

            // 판매자 B 상품·variant
            long productBId = insertPublishedProduct(conn, sellerBId, "E2E-050A-상품B");
            String productBName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productBId);
            long variantBId = insertVariant(conn, productBId, "E2E-050A-SKU-B-" + System.nanoTime());

            // 구매자 + 주문(paid)
            long buyerId = insertConsumer(conn, "e2e-050a-buyer-" + System.nanoTime() + "@example.com");
            orderId = insertOrder(conn, buyerId, "paid");

            // 항목 A(owner_id=sellerAId) + 항목 B(owner_id=sellerBId) — 한 주문에 두 판매자 항목
            long itemAId = insertOrderItem(conn, orderId, variantAId, sellerAId, productAName);
            long itemBId = insertOrderItem(conn, orderId, variantBId, sellerBId, productBName);

            // 판매자 A 배송(preparing) + 판매자 B 배송(preparing) — 각자 시드
            sellerAShipmentId = insertShipmentPreparing(conn, orderId, itemAId, sellerAId);
            sellerBShipmentId = insertShipmentPreparing(conn, orderId, itemBId, sellerBId);
        }

        // -----------------------------------------------------------------------
        // 판매자 A: 배송 시작 → 배송 완료
        // -----------------------------------------------------------------------
        page.navigate("/login");
        submitLogin(sellerAEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 배송 A 시작 폼 노출 확인 (status=preparing → ship-form-section 렌더)
        Locator shipFormA = page.locator(
                "form[action$='/seller/shipments/" + sellerAShipmentId + "/ship']");
        assertThat(shipFormA).isVisible();

        shipFormA.locator("input[name='carrier']").fill("CJ대한통운");
        shipFormA.locator("input[name='trackingNumber']").fill("TRK-050A-A-" + System.nanoTime());
        shipFormA.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 시작")).click();

        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 시작되었습니다.")).isVisible();

        // 배송 A 완료
        Locator deliverFormA = page.locator(
                "form[action$='/seller/shipments/" + sellerAShipmentId + "/deliver']");
        assertThat(deliverFormA).isVisible();
        deliverFormA.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료")).click();

        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 완료 처리되었습니다.")).isVisible();

        // A 완료 후 DB 교차검증: A shipment=delivered, B는 아직 preparing, order=shipping(부분)
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + sellerAShipmentId, "delivered");
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + sellerBShipmentId, "preparing");
            // B 미완 → 전체 롤업 미발생 → order는 shipping(첫 ship으로 markShipping)
            assertScalar(conn,
                    "SELECT status FROM orders WHERE id=" + orderId, "shipping");
        }

        // -----------------------------------------------------------------------
        // 판매자 B: 배송 시작 → 배송 완료 (새 브라우저 컨텍스트)
        // -----------------------------------------------------------------------
        // 기존 컨텍스트(판매자 A) 닫고 판매자 B 컨텍스트 열기
        context.close();
        openContextForNewUser();

        page.navigate("/login");
        submitLogin(sellerBEmail, PASSWORD);
        page.navigate(BASE_URL + "/seller/orders");

        // 배송 B 시작
        Locator shipFormB = page.locator(
                "form[action$='/seller/shipments/" + sellerBShipmentId + "/ship']");
        assertThat(shipFormB).isVisible();

        shipFormB.locator("input[name='carrier']").fill("한진택배");
        shipFormB.locator("input[name='trackingNumber']").fill("TRK-050A-B-" + System.nanoTime());
        shipFormB.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 시작")).click();

        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 시작되었습니다.")).isVisible();

        // 배송 B 완료
        Locator deliverFormB = page.locator(
                "form[action$='/seller/shipments/" + sellerBShipmentId + "/deliver']");
        assertThat(deliverFormB).isVisible();
        deliverFormB.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("배송 완료")).click();

        assertThat(page).hasURL(Pattern.compile(".*/seller/orders.*"));
        assertThat(page.getByText("배송이 완료 처리되었습니다.")).isVisible();

        // A·B 모두 deliver → order delivered 롤업 DB 교차검증
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + sellerAShipmentId, "delivered");
            assertScalar(conn,
                    "SELECT status FROM shipments WHERE id=" + sellerBShipmentId, "delivered");
            assertScalar(conn,
                    "SELECT status FROM orders WHERE id=" + orderId, "delivered");
        }

        // 판매자 B 화면에서 주문 상태 라벨 '배송 완료' 확인
        // (seller/orders.html: badge-order-status, delivered → '배송 완료')
        page.navigate(BASE_URL + "/seller/orders");
        assertThat(page.locator(".badge-order-status").first()).hasText("배송 완료");
    }

    // ==========================================================================
    // 시나리오 B — admin 배송별 seller 표기 양분기
    // ==========================================================================

    /**
     * admin 주문 이행 화면({@code /admin/orders})에서 두 배송의 {@code .shipment-seller} 라벨을 검증:
     * <ul>
     *   <li>(a) 판매자가 생성한 배송(seller_id=판매자 userId) → {@code sellerLabel} = 판매자 이름(실명)</li>
     *   <li>(b) admin이 직접 생성한 배송(seller_id=null) → {@code sellerLabel} = "관리자 직접 처리"</li>
     * </ul>
     *
     * <p>정적 리뷰 사각지대([[verify-admin-list-page-features-with-e2e]]): null 분기는 템플릿에서 조건
     * 없이 항상 렌더되지만, sellerLabel이 null·빈값일 때 빈 라벨이 나올 수 있어 E2E로만 검증 가능.
     */
    @Test
    @DisplayName("(B) admin 배송 표기 양분기 — 판매자 생성 배송=판매자명, admin 생성 배송='관리자 직접 처리'")
    void adminOrders_shipmentSellerLabel_twoVariants() throws Exception {
        // 판매자 계정 생성 — sellerLabel에 이 이름이 표시되어야 함
        String sellerEmail = uniqueEmail();
        signup(sellerEmail, PASSWORD);
        promoteRole(sellerEmail, "SELLER");

        // 판매자 이름은 signup 시 "E2E 사용자"로 고정 (AbstractE2eTest.signup 참조)
        final String expectedSellerName = "E2E 사용자";

        long orderId;
        long sellerShipmentId;
        long adminShipmentId;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long sellerId = scalarLong(conn, "SELECT id FROM users WHERE email=" + q(sellerEmail));

            // 판매자 상품·variant
            long productId = insertPublishedProduct(conn, sellerId, "E2E-050B-상품");
            String productName = scalarStr(conn, "SELECT name FROM products WHERE id=" + productId);
            long variantId = insertVariant(conn, productId, "E2E-050B-SKU-" + System.nanoTime());

            // 구매자 + 주문(paid)
            long buyerId = insertConsumer(conn, "e2e-050b-buyer-" + System.nanoTime() + "@example.com");
            orderId = insertOrder(conn, buyerId, "paid");

            // 두 항목 — 각각 다른 배송에 배정
            long itemSellerOwned = insertOrderItem(conn, orderId, variantId, sellerId, productName);

            // 두 번째 항목은 동일 상품의 별도 order_item으로 시드 (admin 직접 배송용)
            long variantId2 = insertVariant(conn, productId, "E2E-050B-SKU2-" + System.nanoTime());
            long itemAdminOwned = insertOrderItem(conn, orderId, variantId2, sellerId, productName + "(2번)");

            // (a) 판매자 배송: seller_id=sellerId 명시
            sellerShipmentId = insertShipmentPreparingWithSeller(conn, orderId, itemSellerOwned, sellerId);

            // (b) admin 직접 배송: seller_id=NULL — 레거시/admin 직접 처리 시나리오 재현
            adminShipmentId = insertShipmentPreparingAdminOwned(conn, orderId, itemAdminOwned);
        }

        // admin 로그인
        page.navigate("/login");
        submitLogin(ADMIN_EMAIL, ADMIN_PASSWORD);

        // /admin/orders 접근
        page.navigate(BASE_URL + "/admin/orders");

        // (a) 판매자 생성 배송 — .shipment-seller 에 판매자 이름 표시
        // 배송 #N 블록 안의 .shipment-seller 를 shipmentId로 특정
        Locator sellerShipmentBlock = page.locator(
                ".shipment-item:has(.shipment-id:has-text('배송 #" + sellerShipmentId + "'))");
        assertThat(sellerShipmentBlock).isVisible();
        Locator sellerLabel = sellerShipmentBlock.locator(".shipment-seller");
        assertThat(sellerLabel).isVisible();
        assertThat(sellerLabel).hasText(expectedSellerName);

        // (b) admin 직접 배송 — .shipment-seller 에 "관리자 직접 처리" 표시
        Locator adminShipmentBlock = page.locator(
                ".shipment-item:has(.shipment-id:has-text('배송 #" + adminShipmentId + "'))");
        assertThat(adminShipmentBlock).isVisible();
        Locator adminSellerLabel = adminShipmentBlock.locator(".shipment-seller");
        assertThat(adminSellerLabel).isVisible();
        assertThat(adminSellerLabel).hasText("관리자 직접 처리");
    }

    // ==========================================================================
    // 헬퍼: 브라우저 컨텍스트 재초기화 (판매자 전환 시)
    // ==========================================================================

    /**
     * 판매자 A → B 전환 시 새 컨텍스트·페이지를 열어 세션을 격리한다.
     * {@link AbstractE2eTest#openContext()}와 동일 로직 — AfterEach/BeforeEach 생명주기 밖에서
     * 수동으로 호출할 때 사용.
     */
    private void openContextForNewUser() {
        Browser browser = context.browser();
        context = browser.newContext(new Browser.NewContextOptions().setBaseURL(BASE_URL));
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true).setSnapshots(true).setSources(true));
        page = context.newPage();
    }

    // ==========================================================================
    // JDBC 시드 헬퍼
    // ==========================================================================

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
                "INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', ?, 'CONSUMER')")) {
            ps.setString(1, email);
            ps.setString(2, E2ePii.enc("구매자E2E050"));
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email=" + q(email));
    }

    /**
     * ON_SALE 상품 시드. 멱등 실행을 위해 nanosecond 접미사를 붙이지 않고 namePrefix로 유일성 보장.
     * 실제 유일성은 호출 측에서 고유 prefix를 사용해 보장한다.
     */
    private long insertPublishedProduct(Connection conn, long ownerId, String namePrefix) throws SQLException {
        String name = namePrefix + "-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) "
                + "VALUES (?, ?, 10000, 'ON_SALE', ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task050 공존 정합");
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
        String orderNumber = "E2E-050-ORD-" + System.nanoTime() + "-" + (seqCounter++);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders "
                + "(user_id, order_number, status, items_amount, discount_amount, "
                + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                + "VALUES (?, ?, ?, 20000, 0, 0, 20000, ?, ?, ?, ?)")) {
            ps.setLong(1, buyerId);
            ps.setString(2, orderNumber);
            ps.setString(3, status);
            ps.setString(4, E2ePii.enc("수령인E2E"));
            ps.setString(5, E2ePii.enc("010-0000-0000"));
            ps.setString(6, E2ePii.enc("12345"));
            ps.setString(7, E2ePii.enc("서울시"));
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM orders WHERE order_number=" + q(orderNumber));
    }

    /**
     * order_item 시드 — {@code owner_id} 반드시 명시(소유권 스코핑 핵심).
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
     * preparing 상태 shipment + shipment_item 시드 (seller_id 명시).
     * 판매자 생성 배송 시나리오용.
     *
     * <p>실제 흐름에서는 {@code createShipmentInternal}이 shipment 생성 후
     * {@code order.markPreparing()}을 호출해 {@code paid→preparing}으로 전이시킨다.
     * JDBC 직접 삽입은 이 전이를 건너뛰므로, shipment 삽입 직후
     * {@code UPDATE orders SET status='preparing'}으로 동일한 사전 상태를 재현한다.
     * — {@code ship()} 방어 가드({@code paid} 시 409)를 통과시키기 위함.
     *
     * <p>시나리오 B의 {@code insertShipmentPreparingWithSeller}/{@code insertShipmentPreparingAdminOwned}는
     * ship/deliver를 호출하지 않으므로 order status 보정 불필요.
     *
     * @return 생성된 shipment.id
     */
    private long insertShipmentPreparing(Connection conn, long orderId, long orderItemId,
                                         long sellerId) throws SQLException {
        long shipmentId = insertShipmentPreparingWithSeller(conn, orderId, orderItemId, sellerId);
        // paid→preparing 전이 미러: 실제 createShipmentInternal의 markPreparing() 효과 재현
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status = 'preparing' WHERE id = ?")) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
        return shipmentId;
    }

    /**
     * preparing 상태 shipment + shipment_item 시드 (seller_id 명시).
     * admin 화면 seller 라벨 검증용 — seller_id 있는 분기.
     *
     * @return 생성된 shipment.id
     */
    private long insertShipmentPreparingWithSeller(Connection conn, long orderId, long orderItemId,
                                                   long sellerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO shipments (order_id, seller_id, status) VALUES (?, ?, 'preparing')")) {
            ps.setLong(1, orderId);
            ps.setLong(2, sellerId);
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

    /**
     * preparing 상태 shipment + shipment_item 시드 (seller_id=NULL).
     * admin 직접 처리 배송 시나리오용 — "관리자 직접 처리" sellerLabel 분기.
     *
     * @return 생성된 shipment.id
     */
    private long insertShipmentPreparingAdminOwned(Connection conn, long orderId,
                                                   long orderItemId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO shipments (order_id, seller_id, status) VALUES (?, NULL, 'preparing')")) {
            ps.setLong(1, orderId);
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

    // ==========================================================================
    // JDBC 유틸리티
    // ==========================================================================

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

    private static String q(String v) {
        return (char) 39 + v + (char) 39;
    }
}
