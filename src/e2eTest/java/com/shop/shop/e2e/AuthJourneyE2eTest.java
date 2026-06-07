package com.shop.shop.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 인증 핵심 여정 종단(E2E) 스모크 — 회원가입 / 로그인(성공·실패) / 로그아웃·세션 종료.
 *
 * <p>Thymeleaf SSR + Spring Security(폼 로그인/CSRF/세션) 통합 경로를 실제 브라우저로 검증한다.
 * 얇은 핵심 여정만 다룬다. 폼 필드 단위 검증(비번 불일치 등)·권한 분기 세부는 MockMvc/슬라이스 테스트가 담당한다.
 *
 * <p>수명주기·인증 헬퍼는 {@link AbstractE2eTest} 참고.
 */
class AuthJourneyE2eTest extends AbstractE2eTest {

    @Test
    @DisplayName("회원가입 후 로그인하면 인증된 홈에 진입한다")
    void signupThenLoginReachesAuthenticatedHome() {
        String email = signupAndLogin();

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
        signupAndLogin();

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
}
