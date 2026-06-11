package com.shop.shop.order.dto;

import java.util.List;

/**
 * 관리자 주문 이행 화면 View DTO.
 *
 * <p>admin facade {@code AdminOrderFulfillmentFacade.listFulfillableOrders}의 반환 타입.
 * {@code paid}/{@code preparing} 상태의 주문과 미발송 항목, 기존 배송 현황을 담는다.
 *
 * <p>Entity·ownerId·로컬 경로 미노출. View 전용 scalar DTO.
 *
 * @param orderId       주문 ID
 * @param orderNumber   주문 번호
 * @param status        주문 상태 (lowercase: paid/preparing)
 * @param unshippedItems 미발송 항목 목록 (배송 생성 폼 렌더에 사용)
 * @param shipments     기존 배송 현황 목록 (배송 상태·포함 항목 표시)
 */
public record AdminOrderFulfillmentView(
        long orderId,
        String orderNumber,
        String status,
        List<UnshippedItem> unshippedItems,
        List<ShipmentResponse> shipments
) {

    /**
     * 미발송 주문 항목 DTO (배송 생성 폼 체크박스에 사용).
     *
     * @param orderItemId 주문 항목 ID
     * @param productName 상품명 (주문 시점 스냅샷)
     * @param quantity    수량
     */
    public record UnshippedItem(
            long orderItemId,
            String productName,
            int quantity
    ) {}
}
