package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 041 관리자 카테고리 관리 화면 E2E.
 *
 * <p>검증: ① ADMIN이 /admin/categories에서 카테고리 등록→목록 반영→삭제→목록에서 사라짐,
 * ② 비ADMIN(CONSUMER)은 관리 화면 접근 차단.
 */
class AdminCategoryManagementE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    @Test
    @DisplayName("(1) ADMIN: 카테고리 등록 → 목록 반영 → 삭제 → 사라짐")
    void adminRegistersAndDeletesCategory() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "ADMIN");
        submitLogin(email, PASSWORD);

        String name = "E2E카테고리-" + System.nanoTime();
        String slug = "e2e-cat-" + System.nanoTime();

        page.navigate(BASE_URL + "/admin/categories");
        assertThat(page.locator(".page-title")).hasText("카테고리 관리");

        // 등록
        page.locator("#name").fill(name);
        page.locator("#slug").fill(slug);
        page.locator("#sortOrder").fill("0");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("등록")).click();

        // 목록 반영 + flash
        assertThat(page.getByText("카테고리가 등록되었습니다")).isVisible();
        Locator row = page.locator("tr").filter(new Locator.FilterOptions().setHasText(name));
        assertThat(row).isVisible();

        // 삭제(confirm 수락)
        page.onDialog(dialog -> dialog.accept());
        row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("삭제")).click();

        // 목록에서 사라짐 + flash
        assertThat(page.getByText("카테고리가 삭제되었습니다")).isVisible();
        assertThat(page.getByText(name)).hasCount(0);
    }

    @Test
    @DisplayName("(2) 비ADMIN(CONSUMER): 카테고리 관리 화면 접근 차단")
    void nonAdminCannotAccessCategoryManagement() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD); // CONSUMER 기본 권한

        page.navigate(BASE_URL + "/admin/categories");

        // 관리 화면이 렌더되지 않음(등록 폼 입력칸 부재)
        assertThat(page.locator("#name")).hasCount(0);
    }

    private void promoteRole(String email, String role) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }
}
