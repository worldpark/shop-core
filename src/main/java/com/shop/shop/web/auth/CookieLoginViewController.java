package com.shop.shop.web.auth;

import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.member.spi.ViewAuthFacade;
import com.shop.shop.security.AuthCookies;
import com.shop.shop.security.AuthTokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * View(브라우저) 로그인·로그아웃 컨트롤러.
 *
 * <p>formLogin 제거 후 POST /login·/logout을 직접 처리한다.
 * 도메인 로직은 {@link ViewAuthFacade}(member.spi)를 경유한다.
 * 쿠키 I/O(Set-Cookie / readAccess)는 이 컨트롤러가 {@link AuthCookies}(security OPEN 모듈)를 통해 직접 수행한다.
 *
 * <p>설계 노트: plan §2는 ViewAuthService(member/service) 직접 주입을 명시했으나
 * WebModuleStructureTest·ModularityTests(web→member.service 금지, facade는 서블릿 타입 비노출) 위반으로
 * spi facade 경유 + 쿠키 I/O를 web으로 이관하는 방향으로 구현했다.
 *
 * <p>로그인 폼 계약:
 * <ul>
 *   <li>action: {@code /login} (formLogin과 동일)</li>
 *   <li>파라미터: {@code username}(이메일), {@code password}</li>
 *   <li>성공: SavedRequest URL 또는 {@code /} 로 redirect</li>
 *   <li>실패: {@code /login?error} redirect (템플릿 {@code param.error} 분기 유지)</li>
 * </ul>
 *
 * <p>로그아웃 폼 계약:
 * <ul>
 *   <li>action: {@code /logout} (formLogin.logoutUrl과 동일)</li>
 *   <li>성공: {@code /login?logout} redirect (템플릿 {@code param.logout} 분기 유지)</li>
 * </ul>
 *
 * <p>CSRF: 052 {@code CookieCsrfTokenRepository}가 보호 — 폼 _csrf 히든 자동 주입으로 동일 계약 유지.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CookieLoginViewController {

    private static final String LOGIN_PAGE = "/login";
    private static final String LOGIN_ERROR = "/login?error";
    private static final String LOGIN_LOGOUT = "/login?logout";
    private static final String HOME = "/";

    private final ViewAuthFacade viewAuthFacade;
    private final AuthCookies authCookies;
    private final RequestCache requestCache;

    /**
     * POST /login — 폼 username/password 인증 → JWT 쿠키 발급 → redirect.
     *
     * @param username  폼 username 파라미터 (이메일)
     * @param password  폼 password 파라미터
     * @param request   HTTP 요청 (SavedRequest 조회)
     * @param response  HTTP 응답 (Set-Cookie 헤더 추가)
     * @return redirect URL
     */
    @PostMapping(LOGIN_PAGE)
    public String login(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            AuthTokenIssuer.IssuedTokens issued = viewAuthFacade.login(username, password);
            authCookies.writeTokens(response, issued);

            // SavedRequest 복귀 (052 CookieRequestCache) 또는 홈으로
            String redirectUrl = getSavedRequestUrl(request, response);
            log.debug("View 로그인 성공, redirect: {}", redirectUrl);
            return "redirect:" + redirectUrl;

        } catch (InvalidCredentialsException e) {
            log.debug("View 로그인 실패: {}", e.getMessage());
            return "redirect:" + LOGIN_ERROR;
        }
    }

    /**
     * POST /logout — 쿠키 revoke + 제거 → /login?logout redirect.
     *
     * <p>access 쿠키 없어도 clearTokens 수행(fail-safe).
     *
     * @param request  HTTP 요청 (access_token 쿠키 읽기)
     * @param response HTTP 응답 (Set-Cookie 만료 헤더 추가)
     * @return redirect URL
     */
    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        String accessToken = authCookies.readAccess(request);
        viewAuthFacade.revoke(accessToken);
        authCookies.clearTokens(response);
        log.debug("View 로그아웃 완료 → /login?logout");
        return "redirect:" + LOGIN_LOGOUT;
    }

    /**
     * SavedRequest URL 또는 홈 URL 반환.
     * 052 CookieRequestCache에 저장된 원 URL이 있으면 해당 URL, 없으면 "/".
     */
    private String getSavedRequestUrl(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            if (redirectUrl != null && !redirectUrl.isBlank()) {
                requestCache.removeRequest(request, response);
                return redirectUrl;
            }
        }
        return HOME;
    }
}
