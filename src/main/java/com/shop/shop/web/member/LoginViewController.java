package com.shop.shop.web.member;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면 진입점.
 * 로직 없음 — 뷰 이름 반환만 수행.
 * error/logout 파라미터 처리는 템플릿(param.error / param.logout)이 담당.
 *
 * <p>원래 {@code member.controller.LoginViewController}에서 {@code web.member}로 이동.
 */
@Controller
public class LoginViewController {

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }
}
