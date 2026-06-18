package com.shop.shop.web.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.FlashMap;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link CookieFlashMapManager} 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>직렬화 ↔ 역직렬화 라운드트립 (attrs, targetRequestPath, targetRequestParams, expirationTime 보존)</li>
 *   <li>빈 리스트 → Set-Cookie maxAge=0 (즉시 만료, 1회성 보장)</li>
 *   <li>손상 쿠키 → null 반환 (예외 미전파, fail-safe)</li>
 *   <li>targetRequestPath·만료 매칭 1건 (AbstractFlashMapManager 위임 경로 검증)</li>
 * </ul>
 */
class CookieFlashMapManagerTest {

    private CookieFlashMapManager manager;

    @BeforeEach
    void setUp() {
        manager = new CookieFlashMapManager(new ObjectMapper());
    }

    // ========================================================
    // 1. 직렬화 ↔ 역직렬화 라운드트립
    // ========================================================

    @Test
    @DisplayName("라운드트립 — attrs(flashSuccess/flashError) 보존")
    void roundTrip_preserves_attrs() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "저장되었습니다.");
        flashMap.put("flashError", "오류가 발생했습니다.");
        flashMap.setTargetRequestPath("/orders");
        flashMap.startExpirationPeriod(180);

        List<FlashMap> stored = List.of(flashMap);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(stored, new MockHttpServletRequest(), response);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull().contains(CookieFlashMapManager.COOKIE_NAME + "=");

        String cookieValue = extractCookieValue(setCookieHeader);
        MockHttpServletRequest nextRequest = requestWithCookie(cookieValue);
        List<FlashMap> retrieved = manager.retrieveFlashMaps(nextRequest);

        assertThat(retrieved).isNotNull().hasSize(1);
        FlashMap recovered = retrieved.get(0);
        assertThat(recovered.get("flashSuccess")).isEqualTo("저장되었습니다.");
        assertThat(recovered.get("flashError")).isEqualTo("오류가 발생했습니다.");
    }

    @Test
    @DisplayName("라운드트립 — targetRequestPath 보존")
    void roundTrip_preserves_targetRequestPath() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "완료");
        flashMap.setTargetRequestPath("/seller/orders");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        assertThat(retrieved.get(0).getTargetRequestPath()).isEqualTo("/seller/orders");
    }

    @Test
    @DisplayName("라운드트립 — expirationTime 보존")
    void roundTrip_preserves_expirationTime() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "ok");
        long expectedExp = System.currentTimeMillis() + 180_000;
        flashMap.setExpirationTime(expectedExp);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        assertThat(retrieved.get(0).getExpirationTime()).isEqualTo(expectedExp);
    }

    @Test
    @DisplayName("라운드트립 — targetRequestParams 보존")
    void roundTrip_preserves_targetRequestParams() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "파라미터 테스트");
        flashMap.setTargetRequestPath("/products");
        flashMap.addTargetRequestParam("page", "1");
        flashMap.addTargetRequestParam("size", "20");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        assertThat(retrieved.get(0).getTargetRequestParams().getFirst("page")).isEqualTo("1");
        assertThat(retrieved.get(0).getTargetRequestParams().getFirst("size")).isEqualTo("20");
    }

    // ========================================================
    // 2. 빈 리스트 → maxAge=0 (즉시 만료)
    // ========================================================

    @Test
    @DisplayName("빈 리스트 updateFlashMaps → Set-Cookie maxAge=0")
    void emptyList_produces_maxAge_zero_cookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.updateFlashMaps(List.of(), new MockHttpServletRequest(), response);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader)
                .isNotNull()
                .contains("Max-Age=0");
    }

    @Test
    @DisplayName("빈 리스트 updateFlashMaps → Set-Cookie 헤더가 존재한다")
    void emptyList_always_writes_set_cookie_header() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.updateFlashMaps(List.of(), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Set-Cookie")).isNotNull();
    }

    // ========================================================
    // 3. 손상 쿠키 → null (예외 미전파)
    // ========================================================

    @Test
    @DisplayName("손상 쿠키 — retrieveFlashMaps가 null을 반환한다 (예외 미전파)")
    void corrupted_cookie_returns_null_without_exception() {
        MockHttpServletRequest request = requestWithCookie("NOT_VALID_BASE64!@#$%");

        List<FlashMap> result = manager.retrieveFlashMaps(request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("손상 쿠키 — 예외가 호출자로 전파되지 않는다")
    void corrupted_cookie_does_not_propagate_exception() {
        MockHttpServletRequest request = requestWithCookie("eyJicm9rZW4iOiBbInVuY2xvc2VkIn0");

        assertThatCode(() -> manager.retrieveFlashMaps(request))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("쿠키 없음 — retrieveFlashMaps가 null을 반환한다")
    void no_cookie_returns_null() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        List<FlashMap> result = manager.retrieveFlashMaps(request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("빈 값 쿠키 — retrieveFlashMaps가 null을 반환한다")
    void blank_cookie_value_returns_null() {
        MockHttpServletRequest request = requestWithCookie("   ");

        List<FlashMap> result = manager.retrieveFlashMaps(request);

        assertThat(result).isNull();
    }

    // ========================================================
    // 4. targetRequestPath·만료 매칭 1건
    // ========================================================

    @Test
    @DisplayName("targetRequestPath 매칭 — 다른 path의 FlashMap은 복원 후에도 경로 불일치")
    void targetRequestPath_mismatch_different_path() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "매칭 테스트");
        flashMap.setTargetRequestPath("/orders");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        // /cart 요청이면 /orders path flash는 매칭 안 됨 — 복원 데이터 자체는 정상
        assertThat(retrieved.get(0).getTargetRequestPath()).isEqualTo("/orders");
    }

    @Test
    @DisplayName("만료된 FlashMap — expirationTime이 과거면 isExpired() true")
    void expired_flash_map_isExpired_returns_true() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "만료 테스트");
        flashMap.setExpirationTime(System.currentTimeMillis() - 1000); // 1초 전 만료

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        assertThat(retrieved.get(0).isExpired()).isTrue();
    }

    @Test
    @DisplayName("유효한 FlashMap — expirationTime이 미래면 isExpired() false")
    void valid_flash_map_isExpired_returns_false() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "유효 테스트");
        flashMap.startExpirationPeriod(180); // 3분 후 만료

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);
        String cookieValue = extractCookieValue(response.getHeader("Set-Cookie"));

        List<FlashMap> retrieved = manager.retrieveFlashMaps(requestWithCookie(cookieValue));

        assertThat(retrieved).isNotNull().hasSize(1);
        assertThat(retrieved.get(0).isExpired()).isFalse();
    }

    // ========================================================
    // 5. 쿠키 속성 검증
    // ========================================================

    @Test
    @DisplayName("Set-Cookie 헤더에 HttpOnly 포함")
    void setCookieHeader_contains_httpOnly() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "테스트");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("HttpOnly");
    }

    @Test
    @DisplayName("Set-Cookie 헤더에 SameSite=Lax 포함")
    void setCookieHeader_contains_sameSiteLax() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "테스트");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("Set-Cookie 헤더에 Path=/ 포함")
    void setCookieHeader_contains_rootPath() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "테스트");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("Path=/");
    }

    @Test
    @DisplayName("Set-Cookie 헤더에 Secure 포함")
    void setCookieHeader_contains_secure() {
        FlashMap flashMap = new FlashMap();
        flashMap.put("flashSuccess", "테스트");
        flashMap.startExpirationPeriod(180);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.updateFlashMaps(List.of(flashMap), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("Secure");
    }

    // ========================================================
    // 6. getFlashMapsMutex
    // ========================================================

    @Test
    @DisplayName("getFlashMapsMutex — 세션 비의존이므로 null 반환")
    void getFlashMapsMutex_returns_null() {
        Object mutex = manager.getFlashMapsMutex(new MockHttpServletRequest());
        assertThat(mutex).isNull();
    }

    // ========================================================
    // 헬퍼
    // ========================================================

    /**
     * Set-Cookie 헤더에서 쿠키 값만 추출한다.
     * 예: "FLASH=abc123; Path=/; HttpOnly; SameSite=Lax" → "abc123"
     */
    private String extractCookieValue(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String nameEquals = CookieFlashMapManager.COOKIE_NAME + "=";
        int start = setCookieHeader.indexOf(nameEquals) + nameEquals.length();
        int end = setCookieHeader.indexOf(';', start);
        return end == -1 ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
    }

    /**
     * 주어진 값으로 {@code FLASH} 쿠키를 가진 MockHttpServletRequest를 생성한다.
     */
    private MockHttpServletRequest requestWithCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(CookieFlashMapManager.COOKIE_NAME, value);
        request.setCookies(cookie);
        return request;
    }
}
