package com.shop.shop.order.dto;

import java.util.List;

/**
 * 배송 응답 DTO.
 *
 * <p>Entity·ownerId·로컬 경로 미노출. REST 응답 및 admin facade View 응답에 사용한다.
 * status는 lowercase (예: "preparing").
 *
 * @param shipmentId 배송 ID
 * @param orderId    주문 ID
 * @param status     배송 상태 (lowercase: preparing/shipping/delivered)
 * @param items      배송 항목 목록
 */
public record ShipmentResponse(
        long shipmentId,
        long orderId,
        String status,
        List<ShipmentItemResponse> items
) {
}
