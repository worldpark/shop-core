package com.shop.shop.web.member;

import com.shop.shop.member.spi.AdminBootstrapFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면 진입점.
 * error/logout/adminCreated 파라미터 처리는 템플릿(param.error / param.logout / param.adminCreated)이 담당.
 *
 * <p>ADMIN 부트스트랩 게이트:
 * {@link AdminBootstrapFacade#adminExists()}가 false이면 redirect:/setup/admin 으로 유도.
 * 미인증 보호 경로 → View EntryPoint → /login → 게이트 → /setup/admin 흐름으로
 * /login 한 곳에 게이트를 두면 모든 미인증 진입점이 자동 커버된다.
 *
 * <p>원래 {@code member.controller.LoginViewController}에서 {@code web.member}로 이동.
 */
@Controller
@RequiredArgsConstructor
public class LoginViewController {

    private final AdminBootstrapFacade adminBootstrapFacade;

    /**
     * 로그인 화면 표시.
     * GET /login
     *
     * <p>ADMIN이 0명이면 redirect:/setup/admin (부트스트랩 게이트).
     * ADMIN이 1명 이상이면 기존 "auth/login" 반환.
     */
    @GetMapping("/login")
    public String login() {
        if (!adminBootstrapFacade.adminExists()) {
            return "redirect:/setup/admin";
        }
        return "auth/login";
    }
}
