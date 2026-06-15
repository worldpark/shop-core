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

/**
 * Task 032 리뷰(작성·조회·삭제) 브라우저 E2E.
 *
 * <p>MockMvc/슬라이스로는 잡히지 않는 "JDBC 시드 delivered 주문 → 폼 진입 → PRG redirect →
 * 상품 상세 리뷰 섹션 렌더" 전체 경로를 실제 브라우저 + 실제 앱 + 실제 Postgres로 검증한다.
 *
 * <p>시나리오:
 * <ol>
 *   <li>신규 CONSUMER 가입·로그인 → email 확보.</li>
 *   <li>JDBC 시드: delivered 주문 + order_item(+ product/variant) → orderItemId·productId 확보.</li>
 *   <li>리뷰 작성 폼(/reviews/new?orderItemId=) → 5점 선택·내용 입력 → 제출.</li>
 *   <li>상품 상세(/products/{productId}?review) redirect 도달 + flash + 리뷰 섹션 단언.</li>
 *   <li>작성자 표시명 마스킹 단언 (email 원문이 페이지에 없음).</li>
 *   <li>삭제 폼 제출 → /products/{productId} redirect → 리뷰 목록에서 사라짐.</li>
 * </ol>
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있어야 한다.
 */
class ReviewE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    private static final String REVIEW_CONTENT = "E2E 리뷰 내용 — 아주 만족스러운 상품입니다.";

    @Test
    @DisplayName("실구매(delivered) → 리뷰 작성 → 상세 페이지 리뷰 섹션 노출 + 마스킹 단언 → 삭제")
    void writeReview_thenVerifyOnProductDetail_thenDelete() throws Exception {
        // -----------------------------------------------------------------------
        // 1. 신규 CONSUMER 가입·로그인
        // -----------------------------------------------------------------------
        String email = signupAndLogin();

        // -----------------------------------------------------------------------
        // 2. JDBC 시드: delivered 주문 + product/variant/order_item
        // -----------------------------------------------------------------------
        long orderItemId;
        long productId;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            long userId = scalarLong(conn, "SELECT id FROM users WHERE email='" + email + "'");
            long[] ids = insertDeliveredOrderWithItem(conn, userId);
            orderItemId = ids[0];
            productId = ids[1];
        }

        // -----------------------------------------------------------------------
        // 3. 리뷰 작성 폼 → 5점 + 내용 → 제출
        // -----------------------------------------------------------------------
        page.navigate(BASE_URL + "/reviews/new?orderItemId=" + orderItemId);

        // 평점 라디오: id="rating-5" (templates/review/form.html 기준)
        page.locator("#rating-5").click();

        // 내용 textarea: id="content"
        page.locator("#content").fill(REVIEW_CONTENT);

        // 제출 버튼: "리뷰 작성" (reviewForm.reviewId == null 분기)
        page.getByRole(AriaRole.BUTTON,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("리뷰 작성")).click();

        // -----------------------------------------------------------------------
        // 4. PRG redirect: /products/{productId}?review + flash 메시지
        // -----------------------------------------------------------------------
        assertThat(page).hasURL(Pattern.compile(".*/products/" + productId + ".*"));
        assertThat(page.getByText("리뷰가 작성되었습니다.")).isVisible();

        // -----------------------------------------------------------------------
        // 5. 상품 상세 리뷰 섹션: 작성 내용·별점 노출
        //    셀렉터: .review-list .review-item (_review-section.html 기준)
        // -----------------------------------------------------------------------
        Locator reviewList = page.locator(".review-list");
        assertThat(reviewList).isVisible();

        Locator reviewItem = reviewList.locator(".review-item").first();
        assertThat(reviewItem).isVisible();

        // 내용(.review-content) 단언
        assertThat(reviewItem.locator(".review-content")).hasText(REVIEW_CONTENT);

        // 별점 영역(.review-stars)에 ★ 포함 단언
        assertThat(reviewItem.locator(".review-stars")).containsText("★");

        // -----------------------------------------------------------------------
        // 6. 작성자 마스킹 단언 (Task 032 요구: 리뷰 목록 공개 응답에 작성자 email 비노출).
        //    단언 범위를 "리뷰 섹션"으로 한정한다 — nav 바의 로그인 본인 이메일 표시는
        //    Task 032 범위 밖 정상 동작이므로 page 전체가 아니라 리뷰 영역만 검사한다.
        // -----------------------------------------------------------------------
        Locator authorSpan = reviewItem.locator(".review-author");
        assertThat(authorSpan).isVisible();

        // (a) 리뷰 섹션 HTML에 작성자 email 원문이 없어야 한다(타인 PII 비노출)
        String reviewSectionHtml = reviewList.innerHTML();
        if (reviewSectionHtml.contains(email)) {
            throw new AssertionError("마스킹 위반: 작성자 이메일 원문(" + email + ")이 리뷰 섹션에 노출됨");
        }
        // (b) 표시명이 마스킹 형태(로컬파트 앞 2자 + ***)로 노출
        String authorText = authorSpan.textContent().trim();
        if (authorText.contains("@") || !authorText.contains("***")) {
            throw new AssertionError("작성자 표시명 마스킹 형태가 아님: " + authorText);
        }

        // -----------------------------------------------------------------------
        // 7. 삭제: 삭제 버튼(confirm 대화상자 수락) → redirect → 리뷰 사라짐
        // -----------------------------------------------------------------------
        page.onDialog(dialog -> dialog.accept());
        reviewItem.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("삭제")).click();

        // 삭제 성공: /products/{productId} redirect (flash는 선택)
        assertThat(page).hasURL(Pattern.compile(".*/products/" + productId + ".*"));

        // 리뷰 목록이 사라지거나 리뷰 항목이 0개
        // .review-list가 사라지거나 .review-item이 0개여야 함
        boolean listGone = page.locator(".review-list").count() == 0;
        boolean itemsEmpty = page.locator(".review-list .review-item").count() == 0;
        if (!listGone && !itemsEmpty) {
            throw new AssertionError("리뷰 삭제 후 목록에 리뷰 항목이 남아 있습니다.");
        }
    }

    // =============================================================================
    // JDBC 시드 헬퍼
    // =============================================================================

    /**
     * delivered 주문 + product/variant/order_item 시드.
     *
     * @return long[]{orderItemId, productId}
     */
    private long[] insertDeliveredOrderWithItem(Connection conn, long userId) throws SQLException {
        // 1) product
        String productName = "E2E리뷰상품-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO products (name, description, base_price, status, owner_id) "
                        + "VALUES (?, 'E2E 리뷰 테스트용 상품', 10000, 'ON_SALE', ?)")) {
            ps.setString(1, productName);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
        long productId = scalarLong(conn,
                "SELECT id FROM products WHERE name='" + productName + "' ORDER BY id DESC LIMIT 1");

        // 2) product_variant
        String sku = "E2E-REVIEW-SKU-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO product_variants (product_id, sku, price, stock, is_active) VALUES (?, ?, 10000, 10, true)")) {
            ps.setLong(1, productId);
            ps.setString(2, sku);
            ps.executeUpdate();
        }
        long variantId = scalarLong(conn, "SELECT id FROM product_variants WHERE sku='" + sku + "'");

        // 3) order (status='delivered')
        String orderNumber = "ORD-E2E-REVIEW-" + System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders (user_id, order_number, status, items_amount, discount_amount, "
                        + "shipping_fee, final_amount, ship_recipient, ship_phone, ship_postcode, ship_address1) "
                        + "VALUES (?, ?, 'delivered', 10000, 0, 0, 10000, '수령인', '010-9999-8888', '12345', '서울시 강남구')")) {
            ps.setLong(1, userId);
            ps.setString(2, orderNumber);
            ps.executeUpdate();
        }
        long orderId = scalarLong(conn, "SELECT id FROM orders WHERE order_number='" + orderNumber + "'");

        // 4) order_item
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_items (order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                        + "VALUES (?, ?, ?, 10000, 1, 10000)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, variantId);
            ps.setString(3, productName);
            ps.executeUpdate();
        }
        long orderItemId = scalarLong(conn,
                "SELECT id FROM order_items WHERE order_id=" + orderId + " ORDER BY id DESC LIMIT 1");

        return new long[]{orderItemId, productId};
    }

    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
