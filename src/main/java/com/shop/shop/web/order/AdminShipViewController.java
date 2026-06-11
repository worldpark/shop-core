package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 배송 시작 View 핸들러 (020).
 *
 * <p>{@link AdminOrderViewController}는 {@code @RequestMapping("/admin/orders")}로
 * 클래스 레벨 매핑이 고정되어 있어 {@code /admin/shipments/{id}/ship} 경로를
 * 같은 클래스에 넣으면 Spring MVC가 {@code /admin/orders/admin/shipments/{id}/ship}으로
 * 결합한다. plan C3 "절대경로"의 의도를 실현하기 위해 클래스 레벨 매핑 없는 별도 컨트롤러로 분리한다.
 *
 * <p>인가: SecurityConfig View 체인에서 {@code /admin/**} → {@code hasRole("ADMIN")} 보장.
 * 비ADMIN → 403, 비인증 → /login redirect (View 체인 기본 동작).
 *
 * <p>레이어: AdminShipViewController(@Controller) → {@link AdminOrderFulfillmentFacade}(published port)
 * → OrderFulfillmentService → ShipmentRepository·OrderRepository.
 * 도메인 내부 Service·Entity·Repository 직접 참조 금지 (web → order.spi 단방향).
 *
 * <p>PRG 패턴: 성공/실패 모두 {@code redirect:/admin/orders}.
 * Flash 키: {@code flashSuccess} / {@code flashError}.
 *
 * <p>선례: {@link AdminOrderViewController#createShipment} PRG·flash 패턴 복제.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminShipViewController {

    private final AdminOrderFulfillmentFacade adminOrderFulfillmentFacade;

    /**
     * 배송 시작 폼 제출.
     * POST /admin/shipments/{shipmentId}/ship
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/admin/orders} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/admin/orders}.
     * View는 절대 JSON을 반환하지 않는다 (error-response-rule, Constraint).
     *
     * <p>facade 위임만 수행 — 비즈니스 로직 금지 (architecture-rule).
     *
     * @param shipmentId     대상 배송 ID
     * @param carrier        택배사명
     * @param trackingNumber 운송장 번호
     * @param ra             RedirectAttributes (flash 속성 전달)
     * @return redirect:/admin/orders
     */
    @PostMapping("/admin/shipments/{shipmentId}/ship")
    public String ship(
            @PathVariable long shipmentId,
            @RequestParam String carrier,
            @RequestParam String trackingNumber,
            RedirectAttributes ra) {

        try {
            adminOrderFulfillmentFacade.ship(shipmentId, carrier, trackingNumber);
            ra.addFlashAttribute("flashSuccess", "배송이 시작되었습니다.");
        } catch (BusinessException e) {
            log.warn("배송 시작 실패: shipmentId={}, reason={}", shipmentId, e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/admin/orders";
    }
}
