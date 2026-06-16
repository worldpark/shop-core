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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 039 판매 등록 기본가↔Variant 가격 혼동 해소(A 안내 + B prefill) E2E.
 *
 * <p>검증: ① 상품 등록 폼에 기본가 안내 노출, ② variants 생성폼 가격칸이 기본가로 prefill,
 * ③ "수정" 클릭 → variant 가격으로 교체, "취소" → 기본가로 복원.
 */
class SellerPricingClarityE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    // <input type=number>는 DOM .value를 정규화("10000.00"→"10000")하므로 후행 소수 0을 허용하는 정규식으로 단언.
    private static final java.util.regex.Pattern BASE_PRICE = java.util.regex.Pattern.compile("^10000(\\.0+)?$");   // base_price=10000
    private static final java.util.regex.Pattern VARIANT_PRICE = java.util.regex.Pattern.compile("^25000(\\.0+)?$"); // variant 가격=25000

    @Test
    @DisplayName("(1) 상품 등록 폼: 기본가↔결제가 안내 문구 노출")
    void newProductForm_showsBasePriceGuidance() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);

        page.navigate(BASE_URL + "/seller/products/new");
        // 안내 문구(plan §2.A): 실제 결제는 각 옵션 가격으로 — 핵심 문구가 노출되는지
        assertThat(page.getByText("실제 결제는 각 옵션 가격").first()).isVisible();
    }

    @Test
    @DisplayName("(2) variants 생성폼 가격 = 기본가 prefill + 수정→variant가/취소→기본가 복원")
    void variantsForm_prefillsBasePrice_editAndCancelRestore() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);

        String sku = "E2E-PRICE-SKU-" + System.nanoTime();
        long productId; long variantId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email=" + (char) 39 + email + (char) 39);
            productId = insertProduct(conn, userId);                       // base_price=10000
            variantId = insertVariant(conn, productId, sku, 25000, 4, true); // variant 가격 25000(기본가와 다름)
        }

        page.navigate(BASE_URL + "/seller/products/" + productId + "/variants");

        // 가격 안내 문구(plan §2.A) 노출
        assertThat(page.getByText("이 가격으로 실제 결제됩니다.").first()).isVisible();

        // ① 생성폼 가격칸이 기본가(10000)로 prefill(서버 GET prefill)
        assertThat(page.locator("#variantPrice")).hasValue(BASE_PRICE);

        // ② "수정" 클릭 → 해당 variant 가격(25000)으로 교체
        Locator variantRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(sku));
        assertThat(variantRow).isVisible();
        variantRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("수정")).click();
        assertThat(page.locator("#variantPrice")).hasValue(VARIANT_PRICE);

        // ③ "취소" → 다시 기본가(10000)로 복원
        page.locator("#variant-form-cancel").click();
        assertThat(page.locator("#variantPrice")).hasValue(BASE_PRICE);
    }

    // ===== JDBC 헬퍼 (SellerVariantManagementE2eTest와 동일 패턴) =====

    private long insertProduct(Connection conn, long ownerId) throws SQLException {
        String name = "E2E가격명확상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) VALUES (?, ?, 10000, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task039");
            ps.setString(3, "DRAFT");
            ps.setLong(4, ownerId);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM products WHERE name=" + (char) 39 + name + (char) 39 + " ORDER BY id DESC LIMIT 1");
    }

    private long insertVariant(Connection conn, long productId, String sku, int price, int stock, boolean active) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, productId); ps.setString(2, sku); ps.setInt(3, price); ps.setInt(4, stock); ps.setBoolean(5, active); ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM product_variants WHERE sku=" + (char) 39 + sku + (char) 39);
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
