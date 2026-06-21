package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
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
 * 판매자 상품 등록/수정 성공 토스트 + 옵션값 없는 Variant 등록 실패 토스트 E2E.
 *
 * <p>flash 메시지(flashSuccess/flashError)는 app.js가 화면 우측 상단 토스트(.toast)로 변환한다.
 * 토스트는 약 3초 후 자동으로 사라지므로, 단언은 표시 직후(네비게이션 완료 시점)에 수행한다.
 *
 * <p>시나리오:
 * <ol>
 *   <li>(1) 상품 등록 → "상품이 등록되었습니다." 토스트</li>
 *   <li>(2) 상품 수정 → "상품이 수정되었습니다." 토스트</li>
 *   <li>(3) 옵션 있는 상품에서 옵션값 미선택 Variant 등록 → 검증 실패 토스트</li>
 * </ol>
 */
class SellerProductToastE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    /** 옵션값 미선택 Variant 등록 시 서버(V8)가 반환하는 검증 실패 메시지(토스트로 표시). */
    private static final String VARIANT_OPTION_REQUIRED_MSG = "상품의 모든 옵션에 각각 1개씩 값을 선택해야 합니다.";

    @Test
    @DisplayName("(1) 상품 등록: 폼 제출 -> 성공 토스트 노출")
    void registerProduct_showsSuccessToast() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);

        // 토스트는 JS(app.js)로 렌더되므로 콘솔/페이지에러를 stdout으로 캡처(실패 시 원인 규명)
        page.onConsoleMessage(m -> System.out.println("[browser-console] " + m.type() + ": " + m.text()));
        page.onPageError(err -> System.out.println("[browser-pageerror] " + err));

        page.navigate(BASE_URL + "/seller/products/new");

        String productName = "E2E등록상품-" + System.nanoTime();
        page.locator("#name").fill(productName);
        page.locator("#description").fill("E2E 토스트 테스트 상품");
        page.locator("#basePrice").fill("25000");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("등록")).click();

        // PRG redirect: /seller/products/{id}/edit
        assertThat(page).hasURL(Pattern.compile(".*/seller/products/\\d+/edit.*"));
        // 성공 토스트(텍스트 기반 단언 — .toast 컨테이너 안에 렌더됨)
        assertThat(page.locator(".toast").getByText("상품이 등록되었습니다.")).isVisible();
    }

    @Test
    @DisplayName("(2) 상품 수정: 가격 변경 제출 -> 성공 토스트 노출")
    void updateProduct_showsSuccessToast() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);

        page.onConsoleMessage(m -> System.out.println("[browser-console] " + m.type() + ": " + m.text()));
        page.onPageError(err -> System.out.println("[browser-pageerror] " + err));

        long productId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char) 39 + email + (char) 39);
            productId = insertProduct(conn, userId);
        }

        page.navigate(BASE_URL + "/seller/products/" + productId + "/edit");
        page.locator("#basePrice").fill("35000");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("수정")).click();

        assertThat(page).hasURL(Pattern.compile(".*/seller/products/" + productId + "/edit.*"));
        assertThat(page.locator(".toast").getByText("상품이 수정되었습니다.")).isVisible();
    }

    @Test
    @DisplayName("(3) Variant 등록: 옵션값 미선택 -> 실패 토스트 노출")
    void createVariant_withoutOptionValues_showsErrorToast() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);

        page.onConsoleMessage(m -> System.out.println("[browser-console] " + m.type() + ": " + m.text()));
        page.onPageError(err -> System.out.println("[browser-pageerror] " + err));

        // 옵션이 1개 이상 있는 상품을 시드해야 V8(모든 옵션 커버) 검증이 동작한다.
        long productId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char) 39 + email + (char) 39);
            productId = insertProduct(conn, userId);
            long optionId = insertOption(conn, productId, "색상");
            insertOptionValue(conn, optionId, "검정");
        }

        page.navigate(BASE_URL + "/seller/products/" + productId + "/variants");

        // SKU/가격/재고만 채우고 옵션값 체크박스는 선택하지 않는다(Bean검증은 통과, 서버 V8에서 거부).
        page.locator("#variantSku").fill("E2E-NO-OPT-" + System.nanoTime());
        page.locator("#variantPrice").fill("15000");
        page.locator("#variantStock").fill("5");
        Locator submit = page.locator("#variant-form-submit");
        assertThat(submit).hasText("Variant 추가");
        submit.click();

        // PRG redirect 후 실패 토스트 노출
        assertThat(page).hasURL(Pattern.compile(".*/seller/products/" + productId + "/variants.*"));
        assertThat(page.locator(".toast-error")).isVisible();
        assertThat(page.locator(".toast").getByText(VARIANT_OPTION_REQUIRED_MSG)).isVisible();
    }

    // =============================================================
    // JDBC 시드 헬퍼 (SellerVariantManagementE2eTest 검증 패턴 재사용)
    // =============================================================

    private long insertProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E토스트상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E 토스트");
            ps.setString(3, "DRAFT");
            ps.setLong(4, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM products WHERE name=" + (char) 39 + name + (char) 39 + " ORDER BY id DESC LIMIT 1");
    }

    private long insertOption(Connection conn, long productId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_options (product_id, name) VALUES (?, ?)")) {
            ps.setLong(1, productId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_options WHERE product_id=" + productId + " AND name=" + (char) 39 + name + (char) 39);
    }

    private long insertOptionValue(Connection conn, long optionId, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO option_values (option_id, value) VALUES (?, ?)")) {
            ps.setLong(1, optionId);
            ps.setString(2, value);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM option_values WHERE option_id=" + optionId + " AND value=" + (char) 39 + value + (char) 39);
    }

    private void promoteRole(String email, String role) throws Exception {
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
}
