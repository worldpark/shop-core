package com.shop.shop.order.spi;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentResponse;

import java.util.List;

/**
 * 판매자 배송 이행 facade — published port (order 소유).
 *
 * <p>판매자가 자기 owner_id 항목만으로 배송을 생성·시작·완료한다.
 * actorEmail → sellerId 해석은 facade 구현체 내부에서 member.spi로 수행한다.
 * web은 actor.email()만 전달하며 email→id 변환을 직접 수행하지 않는다(048 패턴).
 *
 * <p>소유권 검사:
 * <ul>
 *   <li>배송 생성: order_item.owner_id == sellerId 인 항목만 대상. 타 판매자/미존재 항목 지정 → 404(존재 은닉).</li>
 *   <li>배송 시작/완료: shipment.seller_id == sellerId 인 배송만. 불일치/null(admin 생성) → 404(존재 은닉).</li>
 * </ul>
 *
 * <p>의존 방향: web → order.spi 단방향. order는 web을 참조하지 않는다.
 * web→member.spi 직접 호출 금지(facade 내부 해석).
 *
 * <p>Phase 2(049): 판매자 배송 생성·시작·완료. admin 경로는 별도 {@link AdminOrderFulfillmentFacade}.
 */
public interface SellerFulfillmentFacade {

    /**
     * 판매자 배송 생성.
     *
     * <p>actorEmail → sellerId 해석 후 해당 주문에서 seller 소유(owner_id == sellerId) 미발송 항목으로
     * 배송 1건을 생성한다. seller_id를 스탬프한다.
     *
     * @param actorEmail   form-login principal email (facade 내부에서 sellerId로 해석)
     * @param orderId      대상 주문 ID
     * @param orderItemIds 포함할 주문 항목 ID 목록 (null/빈 = 판매자 소유 미발송 전부)
     * @return 생성된 배송 응답 DTO
     * @throws BusinessException 미존재 주문(404), 소유권 위반(404), 상태충돌/대상0건(409)
     */
    ShipmentResponse createShipment(String actorEmail, long orderId, List<Long> orderItemIds);

    /**
     * 판매자 배송 시작 (preparing → shipping).
     *
     * <p>shipment.seller_id == sellerId 검증 후 기존 ship 로직에 위임한다.
     * ShippingStartedEvent 발행(기존 경로 재사용).
     *
     * @param actorEmail     form-login principal email
     * @param shipmentId     대상 배송 ID
     * @param carrier        택배사명
     * @param trackingNumber 운송장 번호
     * @return 갱신된 배송 응답 DTO
     * @throws BusinessException 미존재/소유권 불일치(404), 상태충돌(409)
     */
    ShipmentResponse ship(String actorEmail, long shipmentId, String carrier, String trackingNumber);

    /**
     * 판매자 배송 완료 (shipping → delivered).
     *
     * <p>shipment.seller_id == sellerId 검증 후 기존 deliver 로직에 위임한다.
     * 멀티셀러 주문 deliver-when-all rollup 재사용.
     *
     * @param actorEmail form-login principal email
     * @param shipmentId 대상 배송 ID
     * @return 배송 완료 응답 DTO ({@code orderDelivered} = 주문 status가 delivered인지)
     * @throws BusinessException 미존재/소유권 불일치(404), 상태충돌(409)
     */
    DeliverResponse deliver(String actorEmail, long shipmentId);
}
