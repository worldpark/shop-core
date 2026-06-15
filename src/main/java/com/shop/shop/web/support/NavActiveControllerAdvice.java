package com.shop.shop.web.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 View(Thymeleaf) 요청에 nav 활성 메뉴 키({@code activeNav})를 모델로 주입한다.
 *
 * <p>배경: layout/base는 nav fragment를 호출할 때 활성 키가 필요한데, 페이지마다 컨트롤러에서
 * 지정하면 누락되기 쉽다(기존엔 base가 {@code nav('home')}을 하드코딩해 모든 화면이 "홈" 활성으로 표시됨).
 * 요청 경로 prefix로 활성 키를 일괄 산출해 한 곳에서 보장한다.
 *
 * <p>scope: {@code com.shop.shop.web} 패키지의 컨트롤러(View)만 대상. REST(@RestController)는 뷰가 없어 무영향.
 * base는 {@code nav(${activeNav} ?: 'home')}로 호출하므로 미산출 시에도 'home'으로 안전 폴백한다.
 */
@ControllerAdvice(basePackages = "com.shop.shop.web")
public class NavActiveControllerAdvice {

    @ModelAttribute("activeNav")
    public String activeNav(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.equals("/")) {
            return "home";
        }
        if (uri.startsWith("/orders") || uri.startsWith("/checkout")) {
            return "orders";
        }
        if (uri.startsWith("/seller-applications")) {
            return "seller-apply";
        }
        if (uri.startsWith("/cart")) {
            return "cart";
        }
        if (uri.startsWith("/products")) {
            return "products";
        }
        if (uri.startsWith("/admin/seller-applications")) {
            return "admin-seller-applications";
        }
        if (uri.startsWith("/admin/members")) {
            return "admin-members";
        }
        if (uri.startsWith("/seller/products/new")) {
            // 등록 폼만 상품 등록 메뉴 활성 — 더 구체적인 경로를 먼저 검사(prefix 순서 의존 주의)
            return "seller-product-new";
        }
        if (uri.startsWith("/seller/products")) {
            // 목록·수정·이미지·옵션은 내 상품 메뉴 활성 (수정/이미지/옵션은 목록의 하위 작업)
            return "seller-products";
        }
        return "home";
    }
}
