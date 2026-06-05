package com.shop.shop.web.home;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 인증 후 홈 화면 진입점 (보호 경로).
 * 로직 없음 — 뷰 이름 반환만 수행.
 * 사용자명 표시는 템플릿에서 sec:authentication="name" 사용 (모델 무전달).
 *
 * <p>원래 {@code home.controller.HomeViewController}에서 {@code web.home}으로 이동.
 * home 모듈은 Task 003에서 제거되고 web 모듈로 통합됨.
 */
@Controller
public class HomeViewController {

    @GetMapping("/")
    public String home() {
        return "home/home";
    }
}
