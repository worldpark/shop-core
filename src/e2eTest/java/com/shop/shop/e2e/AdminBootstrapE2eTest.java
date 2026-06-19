package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 최초 ADMIN 부트스트랩 전체 여정 E2E 검증.
 *
 * <p>전제: ADMIN 계정이 0명인 깨끗한 DB에서 실행된다.
 * 시나리오를 단일 메서드로 순차 검증해 테스트 간 순서 의존을 제거한다.
 *
 * <ol>
 *   <li>부트스트랩 게이트 열림 — /login 접근 시 /setup/admin 으로 redirect</li>
 *   <li>최초 ADMIN 생성 — admin-setup 폼 제출 후 /login?adminCreated redirect</li>
 *   <li>생성된 계정으로 로그인 성공 — 인증된 홈("/") 도달</li>
 *   <li>부트스트랩 게이트 닫힘 — 로그아웃 후 /setup/admin 재접근 시 /login redirect,
 *       /login 접근 시 더 이상 /setup/admin 으로 안 감</li>
 * </ol>
 */
class AdminBootstrapE2eTest extends AbstractE2eTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_NAME = "최초관리자";

    @Test
    @DisplayName("최초 ADMIN 부트스트랩 전체 여정: 게이트 열림 → 계정 생성 → 로그인 → 게이트 닫힘")
    void adminBootstrapFullJourney() {

        // ──────────────────────────────────────────────────────────────
        // 1단계. 부트스트랩 게이트 열림
        //   ADMIN 미존재 상태에서 /login 접근 → /setup/admin 으로 redirect
        // ──────────────────────────────────────────────────────────────
        page.navigate("/login");
        assertThat(page).hasURL(Pattern.compile(".*/setup/admin.*"));

        // admin-setup.html 헤딩 및 안내 문구 가시성 확인
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("관리자 계정 생성"))).isVisible();
        assertThat(page.getByText("현재 등록된 관리자 계정이 없습니다.")).isVisible();

        // ──────────────────────────────────────────────────────────────
        // 2단계. 최초 ADMIN 생성
        //   admin-setup.html 폼: id=email, id=password, id=passwordConfirm, id=name
        //   제출 버튼 텍스트: "관리자 계정 생성"
        // ──────────────────────────────────────────────────────────────
        page.locator("#email").fill(ADMIN_EMAIL);
        page.locator("#password").fill(PASSWORD);
        page.locator("#passwordConfirm").fill(PASSWORD);
        page.locator("#name").fill(ADMIN_NAME);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("관리자 계정 생성")).click();

        // 성공 redirect → /login?adminCreated
        assertThat(page).hasURL(Pattern.compile(".*/login\\?adminCreated.*"));
        // login.html param.adminCreated 안내 문구
        assertThat(page.getByText("관리자 계정이 생성되었습니다. 로그인해 주세요.")).isVisible();

        // ──────────────────────────────────────────────────────────────
        // 3단계. 생성된 계정으로 로그인 성공
        //   현재 페이지(/login)에서 submitLogin 호출
        // ──────────────────────────────────────────────────────────────
        submitLogin(ADMIN_EMAIL, PASSWORD);
        assertThat(page).hasURL(BASE_URL + "/");

        // ──────────────────────────────────────────────────────────────
        // 4단계. 부트스트랩 게이트 닫힘
        //   헤더(BANNER) 로그아웃 버튼으로 세션 종료 후 /setup/admin 재접근 검증
        // ──────────────────────────────────────────────────────────────
        page.getByRole(AriaRole.BANNER)
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("로그아웃"))
                .click();
        assertThat(page).hasURL(Pattern.compile(".*/login\\?logout.*"));

        // ADMIN 존재 → /setup/admin 접근 시 /login 으로 redirect (게이트 닫힘)
        page.navigate("/setup/admin");
        assertThat(page).hasURL(Pattern.compile(".*/login.*"));

        // /login 접근 시 더 이상 /setup/admin 으로 redirect 되지 않고 로그인 폼 표시
        page.navigate("/login");
        assertThat(page).hasURL(Pattern.compile(".*/login.*"));
        assertThat(page.getByLabel("아이디")).isVisible();
        assertThat(page.getByLabel("비밀번호")).isVisible();
    }
}
