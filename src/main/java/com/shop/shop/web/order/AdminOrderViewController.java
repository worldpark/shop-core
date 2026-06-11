package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * 관리자 주문 이행 View 진입점.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>레이어: AdminOrderViewController(@Controller) → {@link AdminOrderFulfillmentFacade}(published port)
 * → OrderFulfillmentService → OrderRepository·ShipmentRepository.
 * 도메인 내부 Service·Entity·Repository 직접 참조 금지 (web → order.spi 단방향).
 *
 * <p>모델 키 계약 (Backend-View Contract):
 * <ul>
 *   <li>{@code orders} — {@code Page<AdminOrderFulfillmentView>} (DTO, Entity 금지)</li>
 * </ul>
 * View name: {@code admin/orders}, redirect: {@code redirect:/admin/orders}
 * Flash 키: {@code flashSuccess} / {@code flashError}
 *
 * <p>선례: {@link com.shop.shop.web.member.AdminMemberViewController} PRG 패턴 복제.
 */
@Slf4j
@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderViewController {

    private final AdminOrderFulfillmentFacade adminOrderFulfillmentFacade;

    /**
     * 이행 대상 주문 목록 화면.
     * GET /admin/orders
     *
     * <p>{@code paid}/{@code preparing} 상태 주문 + 미발송 항목 + 기존 배송 현황을 표시한다.
     * Entity를 모델에 직접 담지 않고 {@link com.shop.shop.order.dto.AdminOrderFulfillmentView} DTO로 반환한다.
     *
     * @param pageable 페이지 요청 (기본 page=0, size=20)
     * @param model    Spring MVC 모델
     * @return view name "admin/orders"
     */
    @GetMapping
    public String list(
            @PageableDefault(size = 20) Pageable pageable,
            Model model) {

        model.addAttribute("orders", adminOrderFulfillmentFacade.listFulfillableOrders(pageable));
        return "admin/orders";
    }

    /**
     * 배송 생성 폼 제출.
     * POST /admin/orders/{orderId}/shipments
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/admin/orders} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/admin/orders}.
     * View는 절대 JSON을 반환하지 않는다 (error-response-rule, Constraint).
     *
     * @param orderId      대상 주문 ID
     * @param orderItemIds 포함할 주문 항목 ID 목록 (미선택 시 미발송 전부를 의미 — null 허용)
     * @param ra           RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/orders
     */
    @PostMapping("/{orderId}/shipments")
    public String createShipment(
            @PathVariable long orderId,
            @RequestParam(required = false) List<Long> orderItemIds,
            RedirectAttributes ra) {

        try {
            adminOrderFulfillmentFacade.createShipment(orderId, orderItemIds);
            ra.addFlashAttribute("flashSuccess", "배송이 생성되었습니다.");
        } catch (BusinessException e) {
            log.warn("배송 생성 실패: orderId={}, reason={}", orderId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/orders";
    }

}
