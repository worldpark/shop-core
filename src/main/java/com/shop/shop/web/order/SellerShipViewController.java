package com.shop.shop.web.order;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.spi.SellerFulfillmentFacade;
import com.shop.shop.web.support.CurrentActor;
import com.shop.shop.web.support.CurrentActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 판매자 배송 View 핸들러 — 배송 시작(ship) + 배송 완료(deliver).
 *
 * <p>{@link SellerOrderViewController}는 {@code @RequestMapping("/seller/orders")}로
 * 클래스 레벨 매핑이 고정되어 있어 {@code /seller/shipments/{id}/ship}·{@code /deliver} 경로를
 * 같은 클래스에 넣으면 Spring MVC가 {@code /seller/orders/seller/shipments/{id}/...}으로
 * 결합한다. 절대경로를 실현하기 위해 클래스 레벨 매핑 없는 별도 컨트롤러로 분리한다.
 * ({@link AdminShipViewController} 선례와 동일 이유.)
 *
 * <p>인가: SecurityConfig View 체인 {@code /seller/**} → {@code hasRole("SELLER")}.
 * 비SELLER → 403, 비인증 → /login redirect.
 *
 * <p>레이어: SellerShipViewController(@Controller) → {@link SellerFulfillmentFacade}(published port).
 * web은 actor.email()만 전달하며 email→sellerId 해석은 facade 내부가 담당한다.
 * web→member.spi 직접 호출 금지.
 *
 * <p>소유권 검사: facade 내부에서 shipment.seller_id == sellerId 검증.
 * 불일치·미존재·null(admin 생성) → 404(존재 은닉). web은 소유권 로직 없음.
 *
 * <p>PRG 패턴: 성공/실패 모두 {@code redirect:/seller/orders}.
 * Flash 키: {@code flashSuccess} / {@code flashError}.
 *
 * <p>선례: {@link AdminShipViewController} PRG·flash 패턴 복제.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SellerShipViewController {

    private final SellerFulfillmentFacade sellerFulfillmentFacade;
    private final CurrentActorResolver currentActorResolver;

    /**
     * 판매자 배송 시작 폼 제출.
     * POST /seller/shipments/{shipmentId}/ship
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/seller/orders} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/seller/orders}.
     *
     * <p>facade 위임만 수행 — 비즈니스 로직 금지 (architecture-rule).
     * 소유권 검사(seller_id 일치·null·미존재 → 404)는 facade 내부 처리.
     *
     * @param auth           SecurityContext 인증 객체
     * @param shipmentId     대상 배송 ID
     * @param carrier        택배사명
     * @param trackingNumber 운송장 번호
     * @param ra             RedirectAttributes (flash 속성 전달)
     * @return redirect:/seller/orders
     */
    @PostMapping("/seller/shipments/{shipmentId}/ship")
    public String ship(
            Authentication auth,
            @PathVariable long shipmentId,
            @RequestParam String carrier,
            @RequestParam String trackingNumber,
            RedirectAttributes ra) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        try {
            sellerFulfillmentFacade.ship(actor.email(), shipmentId, carrier, trackingNumber);
            ra.addFlashAttribute("flashSuccess", "배송이 시작되었습니다.");
        } catch (BusinessException e) {
            log.warn("배송 시작 실패: shipmentId={}, actor={}, reason={}", shipmentId, actor.email(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/orders";
    }

    /**
     * 판매자 배송 완료 폼 제출.
     * POST /seller/shipments/{shipmentId}/deliver
     *
     * <p>성공 시: {@code flashSuccess} + {@code redirect:/seller/orders} (PRG 패턴).
     * 실패 시: {@link BusinessException} catch → {@code flashError} + {@code redirect:/seller/orders}.
     *
     * <p>입력 파라미터 없음 — deliver는 shipmentId만으로 완료 처리 가능.
     * facade 위임만 수행 — 비즈니스 로직 금지 (architecture-rule).
     * 소유권 검사(seller_id 일치·null·미존재 → 404)는 facade 내부 처리.
     *
     * @param auth       SecurityContext 인증 객체
     * @param shipmentId 대상 배송 ID
     * @param ra         RedirectAttributes (flash 속성 전달)
     * @return redirect:/seller/orders
     */
    @PostMapping("/seller/shipments/{shipmentId}/deliver")
    public String deliver(
            Authentication auth,
            @PathVariable long shipmentId,
            RedirectAttributes ra) {

        CurrentActor actor = currentActorResolver.resolve(auth);
        try {
            sellerFulfillmentFacade.deliver(actor.email(), shipmentId);
            ra.addFlashAttribute("flashSuccess", "배송이 완료 처리되었습니다.");
        } catch (BusinessException e) {
            log.warn("배송 완료 실패: shipmentId={}, actor={}, reason={}", shipmentId, actor.email(), e.getMessage());
            ra.addFlashAttribute("flashError", e.getMessage());
        }

        return "redirect:/seller/orders";
    }
}
