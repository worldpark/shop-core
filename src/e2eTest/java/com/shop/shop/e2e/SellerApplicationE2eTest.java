package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
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
 * Task 027 판매자 신청·심사·승격 화면 <b>조건부 가시성</b> E2E.
 *
 * <p>MockMvc/슬라이스로는 잡히지 않는 "쿼리 상태 → 템플릿 조건부 렌더" 공백을 실제 브라우저 +
 * 실제 앱 + 실제 Postgres로 검증한다(MEMORY: verify-admin-list-page-features-with-e2e).
 * 검증 포인트:
 * <ul>
 *   <li>신청자 {@code /seller-applications/apply}: 자격 있으면(eligible) 신청 폼, 없으면 안내 분기(차단 아님)</li>
 *   <li>신청자 {@code /seller-applications/me}: 신청 후 PENDING 배지 노출 + "다시 신청하기" 미노출</li>
 *   <li>관리자 {@code /admin/seller-applications}: PENDING 행에만 승인/반려 폼, 비-PENDING 행은 "처리 완료"</li>
 *   <li>관리자 승인 클릭 → 신청 APPROVED + 신청자 DB role=SELLER 승격(008 changeRole 경로)</li>
 * </ul>
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있고,
 * admin 계정(admin@example.com / Admin1234!)이 시드돼 있어야 한다(AdminAccountSeedTest).
 * 관리자 심사 시나리오의 신청 데이터는 본 테스트가 JDBC로 직접 시드한다(신청 폼 제출을
 * 브라우저로 몰지 않고 상태만 준비 — 심사 화면 조건부 렌더 검증에 집중).
 */
class SellerApplicationE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin1234!";

    @Test
    @DisplayName("CONSUMER: 자격 있는 신규 회원은 /seller-applications/apply에서 신청 폼이 노출되고 안내 분기는 없다")
    void consumerEligible_showsApplicationForm() {
        signupAndLogin();

        page.navigate("/seller-applications/apply");

        // 신청 폼이 노출됨 (eligible=true)
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("판매자 신청"))).isVisible();
        assertThat(page.locator("form.seller-apply-form")).isVisible();
        assertThat(page.getByLabel("상호명")).isVisible();
        assertThat(page.getByLabel("사업자등록번호")).isVisible();
        assertThat(page.getByLabel("담당자 연락처")).isVisible();
        assertThat(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("신청하기"))).isVisible();

        // 자격 미달 안내(.alert-info)는 노출되지 않음
        assertThat(page.locator(".alert.alert-info")).hasCount(0);
    }

    @Test
    @DisplayName("CONSUMER: 신청 제출 → /me에서 PENDING 배지 노출, 재방문한 apply는 폼 대신 안내로 분기")
    void consumerApply_thenPendingState_andApplyBecomesGuidance() {
        signupAndLogin();

        // 신청 폼 제출
        page.navigate("/seller-applications/apply");
        page.getByLabel("상호명").fill("E2E 상점");
        page.getByLabel("사업자등록번호").fill("1234567890");
        page.getByLabel("담당자 연락처").fill("010-1234-5678");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("신청하기")).click();

        // PRG redirect → /me + 성공 flash
        assertThat(page).hasURL(BASE_URL + "/seller-applications/me");
        assertThat(page.getByText("신청이 접수되었습니다.")).isVisible();

        // PENDING 배지 노출, REJECTED 전용 "다시 신청하기"는 없음
        assertThat(page.getByText("심사 중 (PENDING)")).isVisible();
        assertThat(page.getByText("E2E 상점")).isVisible();
        assertThat(page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("다시 신청하기"))).hasCount(0);

        // 재방문 시 PENDING 존재 → eligible=false → 폼 대신 안내 분기(보안 차단 아님)
        page.navigate("/seller-applications/apply");
        assertThat(page).hasURL(BASE_URL + "/seller-applications/apply");
        assertThat(page.locator(".alert.alert-info")).isVisible();
        assertThat(page.getByText("이미 심사 중인 신청이 있습니다.")).isVisible();
        assertThat(page.locator("form.seller-apply-form")).hasCount(0);
    }

    @Test
    @DisplayName("ADMIN: PENDING 행에만 승인/반려 폼이 노출되고, 비-PENDING(REJECTED) 행은 '처리 완료'만 표시")
    void adminList_pendingRowHasForms_nonPendingDoesNot() throws Exception {
        String pendingBiz = "E2E심사대기-" + System.nanoTime();
        String rejectedBiz = "E2E심사반려-" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long u1 = insertConsumer(conn, "e2e-sa-pending-" + System.nanoTime() + "@example.com");
            insertApplication(conn, u1, "PENDING", pendingBiz, null, null);
            long u2 = insertConsumer(conn, "e2e-sa-rejected-" + System.nanoTime() + "@example.com");
            insertApplication(conn, u2, "REJECTED", rejectedBiz, "서류 미비", adminUserId(conn));
        }

        page.navigate("/login");
        submitLogin(ADMIN_EMAIL, ADMIN_PASSWORD);
        page.navigate("/admin/seller-applications?size=200");
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("판매자 신청 심사"))).isVisible();

        // PENDING 행: 승인/반려 폼 노출
        Locator pendingRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(pendingBiz));
        assertThat(pendingRow).isVisible();
        assertThat(pendingRow.locator("form[action$='/approve']")).isVisible();
        assertThat(pendingRow.locator("form[action$='/reject']")).isVisible();
        assertThat(pendingRow.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("승인"))).isVisible();
        assertThat(pendingRow.locator("input[name='rejectReason']")).isVisible();

        // REJECTED 행: 폼 없음 + "처리 완료" 표시
        Locator rejectedRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(rejectedBiz));
        assertThat(rejectedRow).isVisible();
        assertThat(rejectedRow.locator("form[action$='/approve']")).hasCount(0);
        assertThat(rejectedRow.locator("form[action$='/reject']")).hasCount(0);
        assertThat(rejectedRow.getByText("처리 완료")).isVisible();
    }

    @Test
    @DisplayName("ADMIN: 승인 클릭 → flash 성공 + 신청 APPROVED + 신청자 role=SELLER 승격(008 changeRole)")
    void adminApprove_promotesApplicantToSeller() throws Exception {
        String biz = "E2E승인대상-" + System.nanoTime();
        long applicantUserId;
        long applicationId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            applicantUserId = insertConsumer(conn, "e2e-sa-approve-" + System.nanoTime() + "@example.com");
            applicationId = insertApplication(conn, applicantUserId, "PENDING", biz, null, null);
        }

        page.navigate("/login");
        submitLogin(ADMIN_EMAIL, ADMIN_PASSWORD);
        page.navigate("/admin/seller-applications?status=PENDING&size=200");

        // 승인 버튼의 confirm() 대화상자를 수락하도록 등록 (미등록 시 Playwright 기본 dismiss → 폼 미제출)
        page.onDialog(dialog -> dialog.accept());

        Locator pendingRow = page.locator("tr").filter(new Locator.FilterOptions().setHasText(biz));
        assertThat(pendingRow).isVisible();
        pendingRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("승인")).click();

        // PRG redirect + 성공 flash
        assertThat(page).hasURL(Pattern.compile(".*/admin/seller-applications.*"));
        assertThat(page.getByText("승인되었습니다.")).isVisible();

        // DB 교차검증 — 신청 APPROVED + 심사자 기록 + 신청자 role 승격(008 changeRole 경로)
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            assertScalar(conn, "SELECT status FROM seller_application WHERE id=" + applicationId, "APPROVED");
            assertNotNull(conn, "SELECT reviewed_by FROM seller_application WHERE id=" + applicationId);
            assertNotNull(conn, "SELECT decided_at FROM seller_application WHERE id=" + applicationId);
            assertScalar(conn, "SELECT role FROM users WHERE id=" + applicantUserId, "SELLER");
        }
    }

    // =============================================================
    // JDBC 시드/검증 헬퍼 (running Postgres 직접 연결)
    // =============================================================

    private long insertConsumer(Connection conn, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'x', ?, 'CONSUMER')")) {
            ps.setString(1, email);
            ps.setString(2, E2ePii.enc("판매신청E2E"));
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM users WHERE email='" + email + "'");
    }

    /** seller_application 행 시드(테이블명 단수 — V5). status는 대문자 CHECK 값. */
    private long insertApplication(Connection conn, long userId, String status, String businessName,
                                   String rejectReason, Long reviewedBy) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO seller_application "
                        + "(user_id, status, business_name, business_registration_number, contact_phone, "
                        + " reject_reason, reviewed_by, decided_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, "
                        + " CASE WHEN ?='PENDING' THEN NULL ELSE now() END)")) {
            ps.setLong(1, userId);
            ps.setString(2, status);
            ps.setString(3, E2ePii.enc(businessName));
            ps.setString(4, E2ePii.enc("1234567890"));
            ps.setString(5, E2ePii.enc("010-1234-5678"));
            if (rejectReason != null) {
                ps.setString(6, rejectReason);
            } else {
                ps.setNull(6, java.sql.Types.VARCHAR);
            }
            if (reviewedBy != null) {
                ps.setLong(7, reviewedBy);
            } else {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            ps.setString(8, status);
            ps.executeUpdate();
        }
        return scalarLong(conn, "SELECT id FROM seller_application WHERE user_id=" + userId + " ORDER BY id DESC LIMIT 1");
    }

    private long adminUserId(Connection conn) throws SQLException {
        return scalarLong(conn, "SELECT id FROM users WHERE email='" + ADMIN_EMAIL + "'");
    }

    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void assertScalar(Connection conn, String sql, String expected) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            String actual = rs.getString(1);
            if (!expected.equals(actual)) {
                throw new AssertionError("DB 교차검증 실패: [" + sql + "] expected=" + expected + " actual=" + actual);
            }
        }
    }

    private void assertNotNull(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            if (rs.getString(1) == null) {
                throw new AssertionError("DB 교차검증 실패: [" + sql + "] 값이 null");
            }
        }
    }
}
