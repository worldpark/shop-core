package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Task 057 쿠폰 신규 화면 E2E.
 *
 * <p>검증:
 * ① ADMIN: /admin/coupons 쿠폰 등록 → 목록·사용현황 반영 → 중복 코드 거부 + nav "쿠폰 관리" active.
 * ② CONSUMER: /coupons 쿠폰함 쿠폰 발급 → 미사용 배지 → 중복 발급 거부 + nav "쿠폰함" active.
 * ③ CONSUMER(비ADMIN): /admin/coupons 접근 차단(등록 폼 입력칸 부재).
 *
 * <p>셀렉터 근거:
 * - admin/coupons.html: #code, #name, #discountType(select), #value, #startsAt, #endsAt,
 *   data-testid="coupon-submit-btn"(버튼 "등록"), .page-title("쿠폰 관리"),
 *   data-testid="coupon-row", data-testid="coupon-code", data-testid="coupon-usage".
 * - coupon/wallet.html: #code(couponClaimForm), data-testid="coupon-claim-btn"(버튼 "발급받기"),
 *   .page-title("내 쿠폰함"), data-testid="coupon-row", data-testid="badge-unused"("미사용").
 * - fragments/nav.html: active='admin-coupons' → a[href="/admin/coupons"].nav-link-active,
 *   active='coupons' → a[href="/coupons"].nav-link-active.
 * - fragments/messages.html: .alert.alert-error, .alert.alert-success (th:text=flashError/flashSuccess).
 * - DuplicateCouponCodeException: "이미 존재하는 쿠폰 코드입니다."
 * - CouponAlreadyOwnedException: "이미 보유 중인 쿠폰입니다."
 */
class CouponViewE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    // =========================================================================
    // 시나리오 1 — ADMIN: 쿠폰 등록 → 목록·사용현황 반영 → 중복 코드 거부 + nav active
    // =========================================================================

    @Test
    @DisplayName("(1) ADMIN: 쿠폰 등록 → 목록·사용현황 반영 → 중복 코드 거부 + nav 쿠폰 관리 active")
    void adminCreatesCouponAndRejectsDuplicate() throws Exception {
        // --- 준비: ADMIN 권한 부여 후 로그인 ---
        String email = uniqueEmail();
        signup(email, PASSWORD);
        promoteRole(email, "ADMIN");
        submitLogin(email, PASSWORD);

        // --- /admin/coupons 진입 ---
        page.navigate(BASE_URL + "/admin/coupons");

        // 페이지 헤딩 가시성
        assertThat(page.locator(".page-title")).hasText("쿠폰 관리");

        // nav "쿠폰 관리" 링크가 active 클래스를 가져야 함
        // nav.html: th:classappend="${active == 'admin-coupons'} ? ' nav-link-active' : ''
        assertThat(page.locator("a.nav-link-active[href='/admin/coupons']")).isVisible();

        // --- 쿠폰 등록 폼 채우기 ---
        String couponCode = "E2E-CPN-" + System.nanoTime();
        String couponName = "E2E 테스트 쿠폰 " + System.nanoTime();

        // id="code" (admin/coupons.html data-testid="form-code")
        page.locator("#code").fill(couponCode);
        // id="name" (admin/coupons.html data-testid="form-name")
        page.locator("#name").fill(couponName);
        // id="discountType" — select, value="fixed"
        page.locator("#discountType").selectOption("fixed");
        // id="value" (할인 값)
        page.locator("#value").fill("1000");
        // id="startsAt" (datetime-local, yyyy-MM-ddTHH:mm)
        page.locator("#startsAt").fill("2020-01-01T00:00");
        // id="endsAt" (datetime-local)
        page.locator("#endsAt").fill("2099-12-31T23:59");
        // isActive 체크박스: id="isActive" — 활성화 선택
        page.locator("#isActive").check();

        // "등록" 버튼 클릭 (data-testid="coupon-submit-btn", 라벨="등록")
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("등록")).click();

        // --- 등록 성공 flash 및 목록 반영 단언 ---
        // flashSuccess: "쿠폰이 등록되었습니다."
        // app.js(convertFlashMessagesToToasts)가 .flash-region의 .alert를 토스트로 변환하며
        // 원본 inline .alert를 제거한다 → 클래스(.alert.alert-success) 대신 텍스트로 단언한다
        // (AdminCategoryManagementE2eTest의 getByText 패턴과 동형).
        assertThat(page.getByText("쿠폰이 등록되었습니다.")).isVisible();

        // 목록에 방금 등록한 code·name 행 가시성
        // admin/coupons.html: <tr data-testid="coupon-row"> + <code data-testid="coupon-code">
        Locator couponRow = page.locator("[data-testid='coupon-row']")
                .filter(new Locator.FilterOptions().setHasText(couponCode));
        assertThat(couponRow).isVisible();
        assertThat(couponRow).containsText(couponName);

        // 사용현황: usedCount=0, usageLimit=null → "0 / 무제한"
        // admin/coupons.html: <td data-testid="coupon-usage"><span th:text="${c.usedCount}"/>/무제한</td>
        assertThat(couponRow.locator("[data-testid='coupon-usage']")).containsText("0");
        assertThat(couponRow.locator("[data-testid='coupon-usage']")).containsText("무제한");

        // --- 동일 코드 중복 등록 시도 → 거부 flash ---
        page.locator("#code").fill(couponCode);
        page.locator("#name").fill("중복 이름");
        page.locator("#discountType").selectOption("fixed");
        page.locator("#value").fill("500");
        page.locator("#startsAt").fill("2020-01-01T00:00");
        page.locator("#endsAt").fill("2099-12-31T23:59");
        page.locator("#isActive").check();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("등록")).click();

        // DuplicateCouponCodeException 메시지: "이미 존재하는 쿠폰 코드입니다."
        // (flashError → app.js 토스트 변환 → 텍스트로 단언)
        assertThat(page.getByText("이미 존재하는 쿠폰 코드입니다.")).isVisible();
    }

    // =========================================================================
    // 시나리오 2 — CONSUMER: 쿠폰함 발급 → 미사용 배지 → 중복 발급 거부 + nav active
    // =========================================================================

    @Test
    @DisplayName("(2) CONSUMER: 쿠폰함 쿠폰 발급 → 미사용 배지 → 중복 발급 거부 + nav 쿠폰함 active")
    void consumerClaimsCouponAndRejectsDuplicate() throws Exception {
        // --- 준비: 쿠폰 정의 JDBC 직접 시드 (CouponApiE2eTest 패턴) ---
        String couponCode = "E2E-WALLET-" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            insertCoupon(conn, couponCode, "E2E 쿠폰함 테스트 쿠폰", "fixed",
                    "1000", "0", null,
                    "2000-01-01T00:00:00Z", "2099-12-31T23:59:59Z",
                    null, true);
        }

        // --- CONSUMER 회원가입 후 로그인 ---
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD);
        assertThat(page).hasURL(BASE_URL + "/");

        // --- /coupons 진입 ---
        page.navigate(BASE_URL + "/coupons");

        // 페이지 헤딩 가시성
        assertThat(page.locator(".page-title")).hasText("내 쿠폰함");

        // nav "쿠폰함" 링크 active 단언
        // nav.html: active='coupons' → th:classappend ' nav-link-active'
        assertThat(page.locator("a.nav-link-active[href='/coupons']")).isVisible();

        // --- 쿠폰 코드 입력 후 발급 ---
        // coupon/wallet.html: <input id="code" data-testid="coupon-code-input">
        page.locator("#code").fill(couponCode);
        // 발급받기 버튼: data-testid="coupon-claim-btn", 라벨="발급받기"
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("발급받기")).click();

        // 발급 성공 flash: "쿠폰이 발급되었습니다."
        // (flashSuccess → app.js 토스트 변환 → 텍스트로 단언)
        assertThat(page.getByText("쿠폰이 발급되었습니다.")).isVisible();

        // 목록에 해당 쿠폰 행 가시성
        // coupon/wallet.html: <tr data-testid="coupon-row"> + <code data-testid="coupon-code">
        Locator walletRow = page.locator("[data-testid='coupon-row']")
                .filter(new Locator.FilterOptions().setHasText(couponCode));
        assertThat(walletRow).isVisible();

        // "미사용" 배지 단언
        // coupon/wallet.html: <span data-testid="badge-unused">미사용</span>
        assertThat(walletRow.locator("[data-testid='badge-unused']")).isVisible();
        assertThat(walletRow.locator("[data-testid='badge-unused']")).hasText("미사용");

        // --- 동일 코드 중복 발급 시도 → 거부 flash ---
        page.locator("#code").fill(couponCode);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("발급받기")).click();

        // CouponAlreadyOwnedException 메시지: "이미 보유 중인 쿠폰입니다."
        // (flashError → app.js 토스트 변환 → 텍스트로 단언)
        assertThat(page.getByText("이미 보유 중인 쿠폰입니다.")).isVisible();
    }

    // =========================================================================
    // 시나리오 3 — 비ADMIN(CONSUMER): /admin/coupons 접근 차단
    // =========================================================================

    @Test
    @DisplayName("(3) 비ADMIN(CONSUMER): 쿠폰 관리 화면(/admin/coupons) 접근 차단")
    void nonAdminCannotAccessCouponManagement() throws Exception {
        // CONSUMER 기본 권한으로 가입·로그인
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD);

        page.navigate(BASE_URL + "/admin/coupons");

        // 등록 폼 입력칸(#code) 미노출 — ADMIN 전용 화면이 렌더되지 않음
        // AdminCategoryManagementE2eTest (2) 패턴과 동형
        assertThat(page.locator("#code")).hasCount(0);
    }

    // =========================================================================
    // JDBC 헬퍼 — CouponApiE2eTest.insertCoupon 복제
    // =========================================================================

    /**
     * coupons 테이블에 쿠폰 정의 row를 직접 삽입한다 (CouponApiE2eTest 동형).
     *
     * <p>maxDiscount null이면 DB NULL 삽입.
     * starts_at/ends_at은 ISO-8601 문자열을 직접 캐스팅한다.
     */
    private void insertCoupon(Connection conn,
                              String code, String name,
                              String discountType, String value,
                              String minOrderAmount, String maxDiscount,
                              String startsAt, String endsAt,
                              Integer usageLimit, boolean isActive) throws SQLException {
        String sql;
        if (maxDiscount != null) {
            sql = "INSERT INTO coupons "
                    + "(code, name, discount_type, value, min_order_amount, max_discount, "
                    + " starts_at, ends_at, usage_limit, is_active) "
                    + "VALUES (?, ?, ?, ?::numeric, ?::numeric, ?::numeric, "
                    + "?::timestamptz, ?::timestamptz, ?, ?)";
        } else {
            sql = "INSERT INTO coupons "
                    + "(code, name, discount_type, value, min_order_amount, max_discount, "
                    + " starts_at, ends_at, usage_limit, is_active) "
                    + "VALUES (?, ?, ?, ?::numeric, ?::numeric, NULL, "
                    + "?::timestamptz, ?::timestamptz, ?, ?)";
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, code);
            ps.setString(idx++, name);
            ps.setString(idx++, discountType);
            ps.setString(idx++, value);
            ps.setString(idx++, minOrderAmount != null ? minOrderAmount : "0");
            if (maxDiscount != null) {
                ps.setString(idx++, maxDiscount);
            }
            ps.setString(idx++, startsAt);
            ps.setString(idx++, endsAt);
            if (usageLimit != null) {
                ps.setInt(idx++, usageLimit);
            } else {
                ps.setNull(idx++, java.sql.Types.INTEGER);
            }
            ps.setBoolean(idx, isActive);
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // JDBC 헬퍼 — 권한 승격 (AdminCategoryManagementE2eTest 복제)
    // =========================================================================

    private void promoteRole(String email, String role) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE email = ?")) {
            ps.setString(1, role);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }
}
