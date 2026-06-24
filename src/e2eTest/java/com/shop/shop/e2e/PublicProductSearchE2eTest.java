package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개 상품 검색 E2E (Task 061 — ES 읽기 경로 + PG 폴백 + 뷰 컷오버).
 *
 * <p><b>검증 경로</b>: ES 미가용(앱을 {@code SHOP_SEARCH_INDEXER_ENABLED=false}로 기동) 시
 * 검색박스가 PG pg_trgm 폴백 경로로 동작함을 실제 브라우저로 검증한다. Nori 형태소·연관도 랭킹
 * 품질은 Testcontainers ES 통합 테스트({@code ProductSearchQueryIntegrationTest})·단위
 * ({@code EsProductSearchAdapterTest})가 담당한다. 본 테스트는 화면 표면(검색박스·정렬·페이징·
 * 카테고리 필터·클릭스루)과 status 화이트리스트(드리프트 완화)를 데이터로 검증한다.
 *
 * <p><b>데이터</b>: {@code @BeforeAll}에서 JDBC로 유니크 토큰 상품을 직접 시드한다(공유 DB 격리).
 * <ul>
 *   <li>A "…케이스"  — ON_SALE, 카테고리 C, 활성 variant 5,000원 → displayPrice 5,000</li>
 *   <li>B "…가방"    — ON_SALE, 카테고리 C, 활성 variant 50,000원 → displayPrice 50,000</li>
 *   <li>E "…외부"    — ON_SALE, 카테고리 없음, 활성 variant 30,000원 → displayPrice 30,000</li>
 *   <li>D "…비공개"  — DRAFT (검색·목록에서 제외되어야 함 — status 드리프트 완화)</li>
 * </ul>
 * 토큰으로 검색하면 A·B·E(3건)만 매칭, D는 status 필터로 제외된다.
 *
 * <p>실행 전제: {@code SHOP_CORE_BASE_URL}(기본 {@code http://localhost:8080}) 앱 기동 +
 * 동일 DB({@code SHOP_CORE_DB_URL}) 접근. {@code ./gradlew e2eTest}로 실행.
 */
class PublicProductSearchE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    // 유니크 토큰 — 공유 DB의 다른 상품과 매칭 충돌 방지(검색 결과 결정성 보장)
    private static final String TOKEN = "E2E검색" + System.nanoTime();
    private static final String NAME_A = TOKEN + "케이스";
    private static final String NAME_B = TOKEN + "가방";
    private static final String NAME_E = TOKEN + "외부";
    private static final String NAME_D = TOKEN + "비공개";

    private static long categoryId;
    private static long productIdA;

    @BeforeAll
    static void seedProducts() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            categoryId = insertCategory(conn, "E2E검색카테고리-" + System.nanoTime());

            productIdA = insertProduct(conn, NAME_A, "ON_SALE", 10000, categoryId);
            insertVariant(conn, productIdA, 5000, 10);

            long productIdB = insertProduct(conn, NAME_B, "ON_SALE", 20000, categoryId);
            insertVariant(conn, productIdB, 50000, 10);

            long productIdE = insertProduct(conn, NAME_E, "ON_SALE", 30000, null);
            insertVariant(conn, productIdE, 30000, 5);

            // DRAFT — 검색/목록에서 제외되어야 함(드리프트 완화 검증용)
            insertProduct(conn, NAME_D, "DRAFT", 9000, categoryId);
        }
    }

    @AfterAll
    static void cleanupProducts() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            // variant는 products FK ON DELETE CASCADE로 함께 삭제됨
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM products WHERE name LIKE ?")) {
                ps.setString(1, TOKEN + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM categories WHERE id = ?")) {
                ps.setLong(1, categoryId);
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("(1) /products 비인증 접근 → 200 + 검색박스 렌더")
    void productList_unauthenticated_rendersSearchBox() {
        page.navigate("/products");

        assertThat(page).hasURL(BASE_URL + "/products");
        assertThat(page.locator("#keyword")).isVisible();
        assertThat(page.locator("#sort")).isVisible();
        assertThat(searchButton()).isVisible();
    }

    @Test
    @DisplayName("(2) 검색박스로 토큰 검색 → ON_SALE 3건 노출, DRAFT 제외(status 드리프트 완화)")
    void search_viaSearchBox_showsOnSale_excludesDraft() {
        page.navigate("/products");

        // 실제 검색박스 사용(폼 surface 검증)
        page.locator("#keyword").fill(TOKEN);
        searchButton().click();
        page.waitForURL(url -> url.contains("keyword="));

        // ON_SALE 상품 3건 노출
        assertThat(page.getByText(NAME_A, exact())).isVisible();
        assertThat(page.getByText(NAME_B, exact())).isVisible();
        assertThat(page.getByText(NAME_E, exact())).isVisible();
        // DRAFT는 제외(검색엔 안 떠야 함)
        assertThat(page.getByText(NAME_D, exact())).hasCount(0);
        // 빈 결과 메시지 없음
        assertThat(page.locator(".empty-msg")).hasCount(0);
        // 검색어 보존
        assertThat(page.locator("#keyword")).hasValue(TOKEN);
    }

    @Test
    @DisplayName("(3) 정렬(priceAsc/priceDesc) → displayPrice 기준 순서 + 검색어 보존")
    void search_priceSort_ordersByDisplayPrice() {
        page.navigate("/products?keyword=" + enc(TOKEN));

        // priceAsc: A(5,000) < E(30,000) < B(50,000)
        page.locator("#sort").selectOption("priceAsc");
        searchButton().click();
        page.waitForURL(url -> url.contains("sort=priceAsc"));
        assertThat(page.locator("#keyword")).hasValue(TOKEN);
        assertOrder(page.locator(".product-name").allInnerTexts(), NAME_A, NAME_E, NAME_B);

        // priceDesc: B(50,000) > E(30,000) > A(5,000)
        page.locator("#sort").selectOption("priceDesc");
        searchButton().click();
        page.waitForURL(url -> url.contains("sort=priceDesc"));
        assertOrder(page.locator(".product-name").allInnerTexts(), NAME_B, NAME_E, NAME_A);
    }

    @Test
    @DisplayName("(4) 카테고리 필터 + 검색어 조합 → 해당 카테고리 상품만(카테고리 없는 E 제외)")
    void search_categoryFilter_combinesWithKeyword() {
        page.navigate("/products?keyword=" + enc(TOKEN) + "&categoryId=" + categoryId);

        assertThat(page.getByText(NAME_A, exact())).isVisible();
        assertThat(page.getByText(NAME_B, exact())).isVisible();
        // E는 카테고리가 없으므로 필터에서 제외
        assertThat(page.getByText(NAME_E, exact())).hasCount(0);
        assertThat(page.locator("body")).not().containsText("Whitelabel Error");
    }

    @Test
    @DisplayName("(5) 페이징 이동 시 검색어·정렬 보존(size=1 → 3페이지)")
    void search_pagination_preservesKeywordAndSort() {
        page.navigate("/products?keyword=" + enc(TOKEN) + "&sort=priceAsc&size=1");

        // 총 3건, 첫 페이지는 priceAsc 1위 = A
        assertThat(page.getByText("총 3개 상품")).isVisible();
        assertThat(page.getByText(NAME_A, exact())).isVisible();

        // 다음 링크에 검색어·정렬·page=1 보존
        Locator nextLink = page.locator(".page-nav a")
                .filter(new Locator.FilterOptions().setHasText("다음"));
        assertThat(nextLink).hasAttribute("href", Pattern.compile(".*keyword=.*sort=priceAsc.*page=1.*"));

        nextLink.click();
        page.waitForURL(url -> url.contains("page=1"));
        // 2페이지는 priceAsc 2위 = E, 검색어 여전히 보존
        assertThat(page.locator("#keyword")).hasValue(TOKEN);
        assertThat(page.getByText(NAME_E, exact())).isVisible();
    }

    @Test
    @DisplayName("(6) 검색 결과 클릭 → 상세 페이지 진입(클릭스루 SoT)")
    void search_clickThrough_navigatesToDetail() {
        page.navigate("/products?keyword=" + enc(TOKEN));

        // A 상품 카드 링크 클릭
        page.locator("a.product-card-link[href*='/products/" + productIdA + "']").click();

        assertThat(page).hasURL(Pattern.compile(".*/products/" + productIdA + "(\\?.*)?$"));
        assertThat(page.getByText(NAME_A).first()).isVisible();
        assertThat(page.locator("body")).not().containsText("Whitelabel Error");
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    /** "검색" 제출 버튼 로케이터. */
    private Locator searchButton() {
        return page.getByRole(AriaRole.BUTTON,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("검색"));
    }

    private static void assertOrder(List<String> names, String first, String second, String third) {
        int i1 = names.indexOf(first);
        int i2 = names.indexOf(second);
        int i3 = names.indexOf(third);
        assertTrue(i1 >= 0 && i2 >= 0 && i3 >= 0,
                "세 상품이 모두 결과에 있어야 함: " + names);
        assertTrue(i1 < i2 && i2 < i3,
                "정렬 순서가 기대와 다름 (" + first + "<" + second + "<" + third + "): " + names);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static com.microsoft.playwright.Page.GetByTextOptions exact() {
        return new com.microsoft.playwright.Page.GetByTextOptions().setExact(true);
    }

    // JDBC 시드 헬퍼

    private static long insertCategory(Connection conn, String name) throws SQLException {
        String slug = "e2e-search-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO categories (name, slug) VALUES (?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, slug);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM categories WHERE slug=" + quote(slug));
    }

    private static long insertProduct(Connection conn, String name, String status, int basePrice, Long categoryId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, category_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, "E2E Task061 검색 시드");
            ps.setInt(3, basePrice);
            ps.setString(4, status);
            if (categoryId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, categoryId);
            }
            ps.executeUpdate();
        }
        return scalarLong(conn,
                "SELECT id FROM products WHERE name=" + quote(name) + " ORDER BY id DESC LIMIT 1");
    }

    private static void insertVariant(Connection conn, long productId, int price, int stock) throws SQLException {
        String sku = "E2E-SKU-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, ?, ?, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.setInt(3, price);
            ps.setInt(4, stock);
            ps.executeUpdate();
        }
    }

    private static long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String quote(String s) {
        return (char) 39 + s + (char) 39;
    }
}
