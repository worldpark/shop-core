package com.shop.shop.web.order;

import com.shop.shop.order.spi.SellerOrderFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 판매자 주문 조회 View 진입점 (읽기 전용 — Phase 1).
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>레이어: SellerOrderViewController(@Controller) → {@link SellerOrderFacade}(published port).
 * web은 actor.email()만 전달하며 email→sellerId 해석은 facade 내부가 담당한다.
 * ({@link com.shop.shop.web.product.SellerProductStatsViewController} 선례와 동일 패턴).
 * web→member.spi 직접 호출 금지.
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code sellerOrderPage} — {@code Page<SellerOrderView>} (DTO, Entity 금지)</li>
 * </ul>
 * View name: {@code seller/orders}
 *
 * <p>Phase 1: 읽기 전용. 배송 생성/시작/완료(Phase 2 — Task 049) 없음.
 */
@Controller
@RequestMapping("/seller/orders")
@RequiredArgsConstructor
public class SellerOrderViewController {

    private static final String ORDERS_VIEW = "seller/orders";

    private final SellerOrderFacade sellerOrderFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 판매자 주문 목록 화면.
     * GET /seller/orders
     *
     * <p>판매자 소유 항목이 포함된 주문을 최신순으로 페이지네이션해 표시한다.
     * 각 주문에는 해당 판매자 소유 항목만 포함되며, 항목별 배송 상태도 표시한다.
     * 모델 키 {@code sellerOrderPage} — Thymeleaf 예약어(application/session/param/request) 회피.
     *
     * @param auth     SecurityContext 인증 객체
     * @param pageable 페이지 정보 (기본 size=20)
     * @param model    Spring MVC 모델
     * @return view name "seller/orders"
     */
    @GetMapping
    public String list(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable,
            Model model) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        model.addAttribute("sellerOrderPage",
                sellerOrderFacade.listSellerOrders(actor.email(), pageable));
        return ORDERS_VIEW;
    }
}
