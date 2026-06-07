package com.shop.shop.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;
import com.shop.shop.e2e.support.PlaywrightArtifactsExtension;
import com.shop.shop.e2e.support.PlaywrightPageHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 인증 핵심 여정 종단(E2E) 스모크 — 회원가입 / 로그인(성공·실패) / 로그아웃·세션 종료.
 *
 * <p>Thymeleaf SSR + Spring Security(폼 로그인/CSRF/세션) 통합 경로를 실제 브라우저로 검증한다.
 * 얇은 핵심 여정만 다룬다. 폼 필드 단위 검증(비번 불일치 등)·권한 분기 세부는 MockMvc/슬라이스 테스트가 담당한다.
 *
 * <p>전제: {@code SHOP_CORE_BASE_URL}(기본 {@code http://localhost:8080})에 앱이 떠 있어야 한다.
 * 브라우저는 {@code ./gradlew installPlaywrightBrowsers}로 사전 설치, 실행은 {@code ./gradlew e2eTest}.
 * 공유 테스트 서버 대비 데이터 의존을 피하려 매 테스트가 유니크 계정을 직접 생성한다.
 *
 * <p>실패 시 스크린샷·trace는 {@link PlaywrightArtifactsExtension}이 {@code build/e2e-artifacts/}에 저장한다.
 */
@ExtendWith(PlaywrightArtifactsExtension.class)
class AuthJourneyE2eTest implements PlaywrightPageHolder {

    private static final String BASE_URL =
            System.getenv().getOrDefault("SHOP_CORE_BASE_URL", "http://localhost:8080");
    private static final String PASSWORD = "Password123!";

    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext(new Browser.NewContextOptions().setBaseURL(BASE_URL));
        // 실패 시 캡처를 위해 추적 시작 (성공 시에는 저장하지 않고 폐기)
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true).setSnapshots(true).setSources(true));
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close(); // 추적 미저장 시 폐기. 실패 시 Extension이 먼저 stop(path)로 저장함.
        }
    }

    @Test
    @DisplayName("회원가입 후 로그인하면 인증된 홈에 진입한다")
    void signupThenLoginReachesAuthenticatedHome() {
        String email = uniqueEmail();

        signup(email, PASSWORD);
        submitLogin(email, PASSWORD);

        // 인증된 홈 (문자열 매칭은 baseURL 기준 정확 일치 — 글로브 와일드카드 *,? 없음)
        assertThat(page).hasURL(BASE_URL + "/");
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("홈"))).isVisible();
        assertThat(page.getByText("환영합니다, " + email + "님.")).isVisible();
    }

    @Test
    @DisplayName("잘못된 자격증명으로 로그인하면 에러 메시지가 표시된다")
    void loginWithInvalidCredentialsShowsError() {
        // 미등록 유니크 계정으로 로그인 시도 → 인증 실패
        page.navigate("/login");
        submitLogin(uniqueEmail(), "WrongPassword!");

        // failureUrl(/login?error) + 에러 안내, 홈으로 넘어가지 않음
        assertThat(page).hasURL(Pattern.compile(".*/login\\?error.*"));
        assertThat(page.getByText("아이디 또는 비밀번호가 올바르지 않습니다.")).isVisible();
    }

    @Test
    @DisplayName("로그아웃하면 세션이 종료되어 보호 페이지 접근이 로그인으로 리다이렉트된다")
    void logoutEndsSession() {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD);
        assertThat(page).hasURL(BASE_URL + "/"); // 로그인 성공(전제) 확인

        // 헤더(공통 nav)의 로그아웃 제출. 홈 본문에도 동일 버튼이 있어 banner로 스코프해 모호성 제거.
        page.getByRole(AriaRole.BANNER)
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("로그아웃"))
                .click();
        assertThat(page).hasURL(Pattern.compile(".*/login\\?logout.*"));
        assertThat(page.getByText("로그아웃 되었습니다.")).isVisible();

        // 세션 종료 확인 — 인증 필요한 홈("/") 재접근 시 로그인으로 리다이렉트
        page.navigate("/");
        assertThat(page).hasURL(Pattern.compile(".*/login.*"));
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private String uniqueEmail() {
        return "e2e-" + System.nanoTime() + "@example.com";
    }

    /** 회원가입 수행 후 {@code /login?signup}(안내 메시지 포함)에 도달함을 검증한다. */
    private void signup(String email, String password) {
        page.navigate("/signup");
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("회원가입"))).isVisible();

        page.getByLabel("이메일").fill(email);
        page.getByLabel("비밀번호", new Page.GetByLabelOptions().setExact(true)).fill(password);
        page.getByLabel("비밀번호 확인").fill(password);
        page.getByLabel("이름").fill("E2E 사용자");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("회원가입")).click();

        assertThat(page).hasURL(Pattern.compile(".*/login\\?signup.*"));
        assertThat(page.getByText("회원가입이 완료되었습니다. 로그인해 주세요.")).isVisible();
    }

    /** 현재 로그인 페이지에서 자격증명을 제출한다(페이지 이동은 호출 측 책임). */
    private void submitLogin(String email, String password) {
        page.getByLabel("아이디").fill(email);
        page.getByLabel("비밀번호").fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("로그인")).click();
    }

    @Override
    public Page page() {
        return page;
    }

    @Override
    public BrowserContext context() {
        return context;
    }
}
