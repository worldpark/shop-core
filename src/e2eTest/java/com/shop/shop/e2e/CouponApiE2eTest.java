package com.shop.shop.e2e;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 031 쿠폰 소비자 여정 API-level E2E.
 *
 * <p>Playwright {@link APIRequestContext}로 REST 엔드포인트를 직접 호출한다(브라우저 UI 없음).
 * 시나리오:
 * <ol>
 *   <li>쿠폰 정의(coupon row)는 JDBC로 직접 시드한다 — admin API 호출 불필요, test isolation 유지.</li>
 *   <li>신규 CONSUMER를 REST 회원가입 API(POST /api/v1/members/signup)로 생성한다.</li>
 *   <li>REST 로그인(POST /api/v1/auth/login)으로 JWT accessToken 획득.</li>
 *   <li>POST /api/v1/coupons (claim, Authorization: Bearer JWT) → 201 + UserCouponResponse 단언.</li>
 *   <li>GET /api/v1/coupons → 200 + 방금 발급된 쿠폰 포함 단언.</li>
 *   <li>GET /api/v1/coupons/applicable → 200 + JSON 배열 구조 단언.</li>
 *   <li>POST /api/v1/coupons (중복 claim) → 409 단언.</li>
 * </ol>
 *
 * <p>인증 방식: POST /api/v1/auth/login ({"email":"...","password":"..."})
 * → {"accessToken":"...","tokenType":"Bearer",...} → Authorization: Bearer {accessToken}.
 *
 * <p>쿠폰 정의 준비: JDBC 직접 시드 선택 이유 —
 * admin 계정이 항상 시드돼 있다는 보장 없이 JDBC 시드가 더 안정적이고
 * admin JWT 발급 추가 단계를 줄여 테스트를 얇게 유지한다.
 *
 * <p>전제: 앱이 {@code SHOP_CORE_BASE_URL}(기본 localhost:8080)에 떠 있어야 한다.
 */
class CouponApiE2eTest extends AbstractE2eTest {

    private static final String DB_URL =
            System.getenv().getOrDefault("SHOP_CORE_DB_URL", "jdbc:postgresql://localhost:5432/shop_core");
    private static final String DB_USER =
            System.getenv().getOrDefault("SHOP_CORE_DB_USER", "shop_core");
    private static final String DB_PASSWORD =
            System.getenv().getOrDefault("SHOP_CORE_DB_PASSWORD", "shop_core");

    /**
     * 이 테스트 전용 Playwright 인스턴스.
     * AbstractE2eTest의 playwright는 private static이므로 독립 인스턴스를 생성한다.
     * APIRequestContext는 Playwright 인스턴스에서 직접 생성해야 baseURL 지정이 가능하다.
     */
    private Playwright localPlaywright;
    private APIRequestContext apiRequestContext;

    @BeforeEach
    void createApiRequestContext() {
        // AbstractE2eTest의 @BeforeEach(openContext) 이후 실행. page/context는 이미 준비됨.
        // APIRequestContext 전용 Playwright 인스턴스를 별도 생성한다.
        localPlaywright = Playwright.create();
        apiRequestContext = localPlaywright.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(BASE_URL));
    }

    @AfterEach
    void disposeApiRequestContext() {
        if (apiRequestContext != null) {
            apiRequestContext.dispose();
        }
        if (localPlaywright != null) {
            localPlaywright.close();
        }
    }

    @Test
    @DisplayName("쿠폰 claim→쿠폰함 조회→적용가능 조회→중복 claim 409 — REST API-level E2E")
    void couponConsumerJourney() throws Exception {
        // -----------------------------------------------------------------------
        // 1. 쿠폰 정의(coupon row) JDBC 직접 시드
        //    admin API 대신 JDBC 삽입 → admin JWT 불필요, test isolation 유지
        //    컬럼: code, name, discount_type, value, min_order_amount, max_discount,
        //           starts_at, ends_at, usage_limit, is_active (V1__init_schema.sql)
        // -----------------------------------------------------------------------
        String couponCode = "E2E-COUPON-" + System.nanoTime();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            insertCoupon(conn, couponCode, "E2E 테스트 쿠폰", "fixed",
                    "1000", "0", null,
                    "2000-01-01T00:00:00Z", "2099-12-31T23:59:59Z",
                    null, true);
        }

        // -----------------------------------------------------------------------
        // 2. 신규 CONSUMER REST 회원가입
        //    POST /api/v1/members/signup → 201 Created
        // -----------------------------------------------------------------------
        String email = uniqueEmail();
        APIResponse signupResp = apiRequestContext.post("/api/v1/members/signup",
                RequestOptions.create().setData(Map.of(
                        "email", email,
                        "password", PASSWORD,
                        "passwordConfirm", PASSWORD,
                        "name", "쿠폰E2E사용자"
                )));
        assertEquals(201, signupResp.status(),
                "회원가입 201 기대 — 실제: " + signupResp.status() + " / " + signupResp.text());

        // -----------------------------------------------------------------------
        // 3. REST 로그인 → JWT accessToken 획득
        //    POST /api/v1/auth/login → {"accessToken":"...","tokenType":"Bearer",...}
        // -----------------------------------------------------------------------
        APIResponse loginResp = apiRequestContext.post("/api/v1/auth/login",
                RequestOptions.create().setData(Map.of(
                        "email", email,
                        "password", PASSWORD
                )));
        assertEquals(200, loginResp.status(),
                "로그인 200 기대 — 실제: " + loginResp.status() + " / " + loginResp.text());

        com.google.gson.JsonObject loginJson = parseJson(loginResp.text());
        String accessToken = loginJson.get("accessToken").getAsString();
        assertNotNull(accessToken, "accessToken 필드 null");
        assertFalse(accessToken.isBlank(), "accessToken 빈 문자열");

        String bearerHeader = "Bearer " + accessToken;

        // -----------------------------------------------------------------------
        // 4. POST /api/v1/coupons — 쿠폰 발급(claim)
        //    Authorization: Bearer {JWT} + body: {"code": "..."}
        //    기대: 201 Created + UserCouponResponse(code, userCouponId, used=false, ...)
        // -----------------------------------------------------------------------
        APIResponse claimResp = apiRequestContext.post("/api/v1/coupons",
                RequestOptions.create()
                        .setHeader("Authorization", bearerHeader)
                        .setData(Map.of("code", couponCode)));
        assertEquals(201, claimResp.status(),
                "쿠폰 claim 201 기대 — 실제: " + claimResp.status() + " / " + claimResp.text());

        com.google.gson.JsonObject claimJson = parseJson(claimResp.text());
        assertEquals(couponCode, claimJson.get("code").getAsString(),
                "발급된 쿠폰 코드 불일치");
        assertNotNull(claimJson.get("userCouponId"), "userCouponId 필드 없음");
        assertFalse(claimJson.get("used").getAsBoolean(), "발급 직후 used=false 기대");

        // -----------------------------------------------------------------------
        // 5. GET /api/v1/coupons — 쿠폰함 조회
        //    기대: 200 + JSON 배열 + 방금 발급한 couponCode 포함
        // -----------------------------------------------------------------------
        APIResponse listResp = apiRequestContext.get("/api/v1/coupons",
                RequestOptions.create().setHeader("Authorization", bearerHeader));
        assertEquals(200, listResp.status(),
                "쿠폰함 조회 200 기대 — 실제: " + listResp.status() + " / " + listResp.text());

        com.google.gson.JsonArray listJson = parseJsonArray(listResp.text());
        assertTrue(listJson.size() > 0, "쿠폰함이 비어 있음 — 방금 발급한 쿠폰이 포함돼야 함");

        boolean foundInList = false;
        for (int i = 0; i < listJson.size(); i++) {
            com.google.gson.JsonObject item = listJson.get(i).getAsJsonObject();
            if (couponCode.equals(item.get("code").getAsString())) {
                foundInList = true;
                break;
            }
        }
        assertTrue(foundInList, "쿠폰함 목록에 방금 발급한 쿠폰(" + couponCode + ")이 없음");

        // -----------------------------------------------------------------------
        // 6. GET /api/v1/coupons/applicable — 적용 가능 쿠폰 미리보기
        //    기대: 200 OK + 유효한 JSON 배열 (최소 구조 단언 — 빈 배열도 정상 응답)
        // -----------------------------------------------------------------------
        APIResponse applicableResp = apiRequestContext.get("/api/v1/coupons/applicable",
                RequestOptions.create().setHeader("Authorization", bearerHeader));
        assertEquals(200, applicableResp.status(),
                "적용가능 쿠폰 조회 200 기대 — 실제: " + applicableResp.status() + " / " + applicableResp.text());

        // 응답이 유효한 JSON 배열임을 확인 (파싱 예외 없음)
        com.google.gson.JsonArray applicableJson = parseJsonArray(applicableResp.text());
        assertNotNull(applicableJson, "GET /applicable 응답이 JSON 배열이 아님");

        // -----------------------------------------------------------------------
        // 7. POST /api/v1/coupons (중복 claim) → 409 Conflict (1인 1매 제약)
        //    uq_user_coupons_user_coupon UNIQUE(user_id, coupon_id) 위반 경로
        // -----------------------------------------------------------------------
        APIResponse dupClaimResp = apiRequestContext.post("/api/v1/coupons",
                RequestOptions.create()
                        .setHeader("Authorization", bearerHeader)
                        .setData(Map.of("code", couponCode)));
        assertEquals(409, dupClaimResp.status(),
                "중복 claim 409 기대 — 실제: " + dupClaimResp.status() + " / " + dupClaimResp.text());
    }

    // =============================================================================
    // JDBC 시드 헬퍼
    // =============================================================================

    /**
     * coupons 테이블에 쿠폰 정의 row를 직접 삽입한다.
     *
     * <p>max_discount가 null이면 DB NULL 삽입(percent 타입에서 상한 없음).
     * starts_at/ends_at은 ISO-8601 문자열을 직접 캐스팅한다.
     * V1__init_schema.sql 컬럼 기준: code, name, discount_type, value,
     * min_order_amount, max_discount, starts_at, ends_at, usage_limit, is_active.
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

    @SuppressWarnings("unused")
    private long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // =============================================================================
    // JSON 파싱 헬퍼 (Gson — com.google.code.gson, Playwright 의존성에 전이 포함)
    // =============================================================================

    private com.google.gson.JsonObject parseJson(String json) {
        return new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
    }

    private com.google.gson.JsonArray parseJsonArray(String json) {
        return new com.google.gson.Gson().fromJson(json, com.google.gson.JsonArray.class);
    }
}
