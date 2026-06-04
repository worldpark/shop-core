package com.shop.shop.home.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 인증 후 홈 화면 진입점 (보호 경로).
 * 로직 없음 — 뷰 이름 반환만 수행.
 * 사용자명 표시는 템플릿에서 sec:authentication="name" 사용 (모델 무전달).
 */
@Controller
public class HomeViewController {

    @GetMapping("/")
    public String home() {
        return "home/home";
    }
}
