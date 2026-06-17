package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import com.shop.shop.order.spi.SellerOrderFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 판매자 주문 조회·이행 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>레이어: SellerOrderViewController(@Controller)
 * → {@link SellerOrderFacade}(읽기, published port)
 * → {@link SellerFulfillmentFacade}(배송 이행, published port).
 * web은 actor.email()만 전달하며 email→sellerId 해석은 facade 내부가 담당한다.
 * ({@link com.shop.shop.web.product.SellerProductStatsViewController} 선례와 동일 패턴).
 * web→member.spi 직접 호출 금지.
 *
 * <p>모델 키 계약 (view-implementor와 정합):
 * <ul>
 *   <li>{@code sellerOrderPage} — {@code Page<SellerOrderView>} (DTO, Entity 금지)</li>
 * </ul>
 * View name: {@code seller/orders}, redirect: {@code redirect:/seller/orders}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 *
 * <p>배송 시작·완료({@code /seller/shipments/{id}/ship·/deliver})는 클래스 레벨 매핑
 * {@code /seller/orders}와 충돌하므로 {@link SellerShipViewController}로 분리한다.
 * (AdminShipViewController 선례와 동일 이유.)
 */
@Slf4j
@Controller
@RequestMapping("/seller/orders")
@RequiredArgsConstructor
public class SellerOrderViewController {

    private static final String ORDERS_VIEW = "seller/orders";
    private static final String REDIRECT_ORDERS = "redirect:/seller/orders";

    private final SellerOrderFacade sellerOrderFacade;
    private final SellerFulfillmentFacade sellerFulfillmentFacade;
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

    /**
     * 판매자 배송 생성 폼 제출.
     * POST /seller/orders/{orderId}/shipments
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/seller/orders} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/seller/orders}.
     *
     * <p>orderItemIds 미선택(null/빈) 시: 해당 판매자 소유 미발송 항목 전부 포함.
     * 타 판매자 항목 지정 시 facade 내부에서 404 처리(소유권 위반 존재 은닉).
     * web은 actor.email()만 전달하며 email→sellerId 해석은 facade 내부가 담당한다.
     *
     * @param auth         SecurityContext 인증 객체
     * @param orderId      대상 주문 ID
     * @param orderItemIds 포함할 주문 항목 ID 목록 (미선택 시 owned 미발송 전부 — null 허용)
     * @param ra           RedirectAttributes (flash 속성 전달)
     * @return redirect:/seller/orders
     */
    @PostMapping("/{orderId}/shipments")
    public String createShipment(
            Authentication auth,
            @PathVariable long orderId,
            @RequestParam(required = false) List<Long> orderItemIds,
            RedirectAttributes ra) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        try {
            sellerFulfillmentFacade.createShipment(actor.email(), orderId, orderItemIds);
            ra.addFlashAttribute("flashSuccess", "배송이 생성되었습니다.");
        } catch (BusinessException e) {
            log.warn("배송 생성 실패: orderId={}, actor={}, reason={}", orderId, actor.email(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return REDIRECT_ORDERS;
    }
}
