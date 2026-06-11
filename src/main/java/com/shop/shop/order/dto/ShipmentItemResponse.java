package com.shop.shop.order.dto;

/**
 * 배송 항목 응답 DTO.
 *
 * <p>Entity·ownerId·로컬 경로 미노출. REST 응답 및 admin facade View 응답에 사용한다.
 *
 * @param orderItemId 주문 항목 ID
 * @param productName 상품명 (주문 시점 스냅샷)
 * @param quantity    수량
 */
public record ShipmentItemResponse(
        long orderItemId,
        String productName,
        int quantity
) {
}
