package com.shop.shop.member.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 회원 모듈의 로그인 화면 진입점.
 * 로직 없음 — 뷰 이름 반환만 수행.
 * error/logout 파라미터 처리는 템플릿(param.error / param.logout)이 담당.
 */
@Controller
public class LoginViewController {

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }
}
