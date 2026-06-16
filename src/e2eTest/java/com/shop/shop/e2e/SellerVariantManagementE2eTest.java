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

/** Task 038 판매자 옵션/Variant 관리 화면 신규 기능 3종 E2E. */
class SellerVariantManagementE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    @Test
    @DisplayName("(1) 옵션 삭제: confirm 수락 -> 목록 사라짐 + flash")
    void deleteOption_removesOptionFromList_showsFlash() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);
        String optionName = "E2E삭제옵션-" + System.nanoTime();
        long productId; long deleteOptionId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char)39 + email + (char)39);
            productId = insertProduct(conn, userId);
            deleteOptionId = insertOption(conn, productId, optionName);
            insertOptionValue(conn, deleteOptionId, "E2E값X");
        }
        page.navigate(BASE_URL + "/seller/products/" + productId + "/variants");
        page.onDialog(dialog -> dialog.accept());
        Locator optionItem = page.locator(".option-item")
                .filter(new Locator.FilterOptions().setHasText(optionName));
        assertThat(optionItem).isVisible();
        optionItem.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("옵션 삭제")).click();
        assertThat(page).hasURL(Pattern.compile(".*/seller/products/" + productId + "/variants.*"));
        assertThat(page.getByText("옵션이 삭제되었습니다.")).isVisible();
        assertThat(page.getByText(optionName)).hasCount(0);
    }

    @Test
    @DisplayName("(2) Variant 삭제: confirm 수락 -> 행 사라짐 + flash")
    void deleteVariant_removesRowFromTable_showsFlash() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);
        String deleteSku = "E2E-DEL-SKU-" + System.nanoTime();
        long productId; long deleteVariantId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char)39 + email + (char)39);
            productId = insertProduct(conn, userId);
            deleteVariantId = insertVariant(conn, productId, deleteSku, 9900, 5, true);
        }
        page.navigate(BASE_URL + "/seller/products/" + productId + "/variants");
        Locator variantRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(deleteSku));
        assertThat(variantRow).isVisible();
        page.onDialog(dialog -> dialog.accept());
        Locator deleteForm = page.locator("form[action$='/variants/" + deleteVariantId + "/delete']");
        assertThat(deleteForm).isVisible();
        deleteForm.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("삭제")).click();
        assertThat(page).hasURL(Pattern.compile(".*/seller/products/" + productId + "/variants.*"));
        assertThat(page.getByText("Variant가 삭제되었습니다.")).isVisible();
        assertThat(page.locator("tr").filter(new Locator.FilterOptions().setHasText(deleteSku))).hasCount(0);
    }

    @Test
    @DisplayName("(3) Variant 수정: 폼 채워짐(SKU/가격/재고/active/optionValueIds) + 취소 복귀 + 제출 반영")
    void editVariant_fillsForm_cancelRestores_submitUpdates() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);
        String originalSku = "E2E-EDIT-SKU-" + System.nanoTime();
        long productId; long redValueId; long blueValueId; long variantId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char)39 + email + (char)39);
            productId = insertProduct(conn, userId);
            long optionId = insertOption(conn, productId, "색상");
            redValueId = insertOptionValue(conn, optionId, "빨강");
            blueValueId = insertOptionValue(conn, optionId, "파랑");
            variantId = insertVariant(conn, productId, originalSku, 10000, 3, true);
            linkVariantValue(conn, variantId, redValueId);
        }
        // JS 오류 진단: 콘솔/페이지에러를 stdout으로 캡처(인라인 스크립트 미동작 원인 규명)
        page.onConsoleMessage(m -> System.out.println("[browser-console] " + m.type() + ": " + m.text()));
        page.onPageError(err -> System.out.println("[browser-pageerror] " + err));
        page.navigate(BASE_URL + "/seller/products/" + productId + "/variants");
        Locator variantRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(originalSku));
        assertThat(variantRow).isVisible();

        // 인라인 스크립트가 리스너를 붙일 시간 보장(load 이후 한 번 더 확인)
        assertThat(page.locator(".variant-edit-btn").first()).isVisible();

        // 수정 버튼 클릭
        variantRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("수정")).click();
        // 폼 제목/버튼 변경 확인
        assertThat(page.locator("#variant-form-title")).hasText("Variant 수정");
        assertThat(page.locator("#variant-form-submit")).hasText("Variant 수정");
        assertThat(page.locator("#variant-form-cancel")).isVisible();
        // 필드 채워짐 확인
        assertThat(page.locator("#variantSku")).hasValue(originalSku);
        assertThat(page.locator("#variantPrice")).hasValue("10000.00"); // price는 BigDecimal(scale 2) → "10000.00"이 충실한 값
        assertThat(page.locator("#variantStock")).hasValue("3");
        assertThat(page.locator("#variantActive")).isChecked();
        // optionValueIds 체크 상태: 빨강 체크됨, 파랑 미체크
        // CSS attribute selector without quotes: input[name=optionValueIds][value=123]
        String redSel = "input[name=optionValueIds][value='" + redValueId + "']";
        String blueSel = "input[name=optionValueIds][value='" + blueValueId + "']";
        assertThat(page.locator(redSel)).isChecked();
        assertThat(page.locator(blueSel)).not().isChecked();

        // 취소 복귀
        page.locator("#variant-form-cancel").click();
        assertThat(page.locator("#variant-form-title")).hasText("새 Variant 추가");
        assertThat(page.locator("#variant-form-submit")).hasText("Variant 추가");
        assertThat(page.locator("#variant-form-cancel")).not().isVisible();
        assertThat(page.locator("#variantSku")).hasValue("");
        assertThat(page.locator(redSel)).not().isChecked();
        assertThat(page.locator(blueSel)).not().isChecked();

        // 다시 수정 모드 후 값 변경 제출
        variantRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("수정")).click();
        page.locator("#variantPrice").fill("19900");
        page.locator("#variantStock").fill("7");
        // 옵션당 값 1개 규칙: 색상을 빨강→파랑으로 '교체'(빨강 해제 후 파랑 체크). 둘 다 체크하면 서버가 정당히 거부함.
        page.locator(redSel).uncheck();
        page.locator(blueSel).check();
        // form action 확인 via JS evaluate
        String formAction = (String) page.evaluate("document.getElementById(" + (char)39 + "variant-form" + (char)39 + ").action");
        if (!formAction.contains("/variants/" + variantId)) {
            throw new AssertionError("form action이 update 엔드포인트 아님: " + formAction);
        }
        page.locator("#variant-form-submit").click();
        assertThat(page).hasURL(Pattern.compile(".*/seller/products/" + productId + "/variants.*"));
        assertThat(page.getByText("Variant가 수정되었습니다.")).isVisible();
        Locator updatedRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(originalSku));
        assertThat(updatedRow).isVisible();
        // 가격 반영 확인(BigDecimal scale 2 → "19900.00")
        assertThat(updatedRow).containsText("19900.00");
        // 재고 반영은 다시 수정 모드로 진입해 data-* 재확인(텍스트 substring 매칭의 strict-mode 취약성 회피)
        updatedRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("수정")).click();
        assertThat(page.locator("#variantStock")).hasValue("7");
        assertThat(page.locator("#variantPrice")).hasValue("19900.00");
    }

    // JDBC 헬퍼

    private long insertProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E옵션관리상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task038");
            ps.setString(3, "DRAFT");
            ps.setLong(4, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM products WHERE name=" + (char)39 + name + (char)39 + " ORDER BY id DESC LIMIT 1");
    }

    private long insertOption(Connection conn, long productId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_options (product_id, name) VALUES (?, ?)")) {
            ps.setLong(1, productId); ps.setString(2, name); ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_options WHERE product_id=" + productId + " AND name=" + (char)39 + name + (char)39);
    }

    private long insertOptionValue(Connection conn, long optionId, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO option_values (option_id, value) VALUES (?, ?)")) {
            ps.setLong(1, optionId); ps.setString(2, value); ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM option_values WHERE option_id=" + optionId + " AND value=" + (char)39 + value + (char)39);
    }

    private long insertVariant(Connection conn, long productId, String sku, int price, int stock, boolean active) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, productId); ps.setString(2, sku); ps.setInt(3, price); ps.setInt(4, stock); ps.setBoolean(5, active); ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + (char)39 + sku + (char)39);
    }

    private void linkVariantValue(Connection conn, long variantId, long optionValueId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO variant_values (variant_id, option_value_id) VALUES (?, ?)")) {
            ps.setLong(1, variantId); ps.setLong(2, optionValueId); ps.executeUpdate();
        }
    }

    private void promoteRole(String email, String role) throws Exception {
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
}
