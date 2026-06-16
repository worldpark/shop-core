package com.shop.shop.web.admin;

import com.shop.shop.web.admin.dto.AdminDashboardView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 관리자 통계 대시보드 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect.
 * 컨트롤러에서 문자열 권한 검사 금지 — SecurityConfig에 위임.
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code dashboard} — {@link AdminDashboardView} (뷰 모델 DTO, "dashboard"는 예약명 아님)</li>
 * </ul>
 * View name: {@code admin/dashboard}
 */
@Controller
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardViewController {

    private static final String DASHBOARD_VIEW = "admin/dashboard";

    private final AdminDashboardAssembler assembler;

    /**
     * 관리자 통계 대시보드 화면.
     * GET /admin/dashboard
     *
     * <p>3개 통계 지표(유저 이용률·상품 판매율·환불율)를 조합해 모델에 바인딩한다.
     * 모델 키 {@code dashboard} — Thymeleaf 예약어({@code application/session/param/request}) 회피.
     *
     * @param model Spring MVC 모델
     * @return view name "admin/dashboard"
     */
    @GetMapping
    public String dashboard(Model model) {
        AdminDashboardView dashboardView = assembler.build();
        model.addAttribute("dashboard", dashboardView);
        return DASHBOARD_VIEW;
    }
}
