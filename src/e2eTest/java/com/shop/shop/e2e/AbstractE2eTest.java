package com.shop.shop.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * E2E 공통 베이스 — Playwright 수명주기, 컨텍스트/페이지, 인증 헬퍼를 제공한다.
 *
 * <p>전제: {@code SHOP_CORE_BASE_URL}(기본 {@code http://localhost:8080})에 앱이 떠 있어야 한다.
 * 브라우저는 {@code ./gradlew installPlaywrightBrowsers}로 사전 설치, 실행은 {@code ./gradlew e2eTest}.
 * 공유 테스트 서버 대비 데이터 의존을 피하려 매 테스트가 유니크 계정을 직접 생성한다.
 *
 * <p>실패 시 스크린샷·trace는 {@link PlaywrightArtifactsExtension}이 {@code build/e2e-artifacts/}에 저장한다.
 * 테스트 클래스는 기본 순차 실행을 전제로 한다(브라우저 정적 자원 공유).
 */
@ExtendWith(PlaywrightArtifactsExtension.class)
abstract class AbstractE2eTest implements PlaywrightPageHolder {

    protected static final String BASE_URL =
            System.getenv().getOrDefault("SHOP_CORE_BASE_URL", "http://localhost:8080");
    protected static final String PASSWORD = "Password123!";

    private static Playwright playwright;
    private static Browser browser;

    protected BrowserContext context;
    protected Page page;

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

    // =============================================================
    // 공통 헬퍼
    // =============================================================

    protected String uniqueEmail() {
        return "e2e-" + System.nanoTime() + "@example.com";
    }

    /** 회원가입 수행 후 {@code /login?signup}(안내 메시지 포함)에 도달함을 검증한다. */
    protected void signup(String email, String password) {
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
    protected void submitLogin(String email, String password) {
        page.getByLabel("아이디").fill(email);
        page.getByLabel("비밀번호").fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("로그인")).click();
    }

    /** 신규 회원 가입 후 로그인까지 마치고 인증된 홈("/")에 도달한다. */
    protected String signupAndLogin() {
        String email = uniqueEmail();
        signup(email, PASSWORD);
        submitLogin(email, PASSWORD);
        assertThat(page).hasURL(BASE_URL + "/");
        return email;
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
