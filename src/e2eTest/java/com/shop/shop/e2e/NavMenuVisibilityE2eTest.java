package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 권한별 nav 메뉴 조건부 가시성 E2E (브라우저).
 *
 * <p>RoleHierarchy(ADMIN > SELLER > CONSUMER)에서 sec:authorize 조건이 의도대로 동작하는지를
 * 실제 렌더 DOM으로 검증한다(MockMvc/단위는 쿼리↔템플릿 가시성 공백을 놓침 — MEMORY 규칙).
 *
 * <ul>
 *   <li>순수 CONSUMER: "판매자 신청"(/seller-applications/apply) 노출, seller/admin 메뉴 미노출</li>
 *   <li>SELLER: "판매자 신청" 미노출(hasRole('CONSUMER') and !hasRole('SELLER')), "내 상품"·"상품 등록" 노출</li>
 *   <li>ADMIN: "판매자 신청" 미노출, "회원 관리"·"판매자 신청 심사" 노출</li>
 * </ul>
 *
 * <p>전제: SHOP_CORE_BASE_URL 앱 기동 + shop_core DB 접근(역할 승격 시드).
 */
class NavMenuVisibilityE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    // href 기반 로케이터(이름 부분일치 모호성 회피 — "판매자 신청" vs "판매자 신청 심사")
    private Locator applyLink()        { return page.locator("nav a[href='/seller-applications/apply']"); }
    private Locator myProductsLink()   { return page.locator("nav a[href='/seller/products']"); }
    private Locator newProductLink()   { return page.locator("nav a[href='/seller/products/new']"); }
    private Locator adminMembersLink() { return page.locator("nav a[href='/admin/members']"); }
    private Locator adminReviewLink()  { return page.locator("nav a[href='/admin/seller-applications']"); }

    @Test
    @DisplayName("순수 CONSUMER: '판매자 신청' 노출, seller/admin 메뉴 미노출")
    void consumer_seesApplyMenu_notSellerNorAdmin() {
        signupAndLogin();               // 신규 CONSUMER 로그인 → "/"
        page.navigate("/");

        assertThat(applyLink()).isVisible();
        assertThat(myProductsLink()).hasCount(0);
        assertThat(newProductLink()).hasCount(0);
        assertThat(adminMembersLink()).hasCount(0);
        assertThat(adminReviewLink()).hasCount(0);
    }

    @Test
    @DisplayName("SELLER: '판매자 신청' 미노출, '내 상품'·'상품 등록' 노출")
    void seller_hidesApplyMenu_showsSellerMenus() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "SELLER");
        submitLogin(email, PASSWORD);
        page.navigate("/");

        assertThat(applyLink()).hasCount(0);
        assertThat(myProductsLink()).isVisible();
        assertThat(newProductLink()).isVisible();
        assertThat(adminMembersLink()).hasCount(0);
    }

    @Test
    @DisplayName("ADMIN: '판매자 신청' 미노출, '회원 관리'·'판매자 신청 심사' 노출")
    void admin_hidesApplyMenu_showsAdminMenus() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "ADMIN");
        submitLogin(email, PASSWORD);
        page.navigate("/");

        assertThat(applyLink()).hasCount(0);
        assertThat(adminMembersLink()).isVisible();
        assertThat(adminReviewLink()).isVisible();
    }

    /** 가입(CONSUMER) 후 DB role을 승격해 로그인 시 해당 권한 authorities가 반영되게 한다. */
    private void promoteRole(String email, String role) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }
}
