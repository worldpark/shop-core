package com.shop.shop.order.dto;

import java.time.Instant;
import java.util.List;

/**
 * 배송 응답 DTO.
 *
 * <p>Entity·ownerId·로컬 경로 미노출. REST 응답 및 admin facade View 응답에 사용한다.
 * status는 lowercase (예: "preparing", "shipping", "delivered").
 *
 * <p>020에서 carrier/trackingNumber/shippedAt 3필드 추가(정합2).
 * 021에서 deliveredAt 1필드 추가(정합3). delivered 아니면 null.
 * preparing 상태의 배송은 carrier/trackingNumber/shippedAt/deliveredAt 모두 null.
 * seller_id 등 내부 필드는 노출 금지.
 *
 * @param shipmentId     배송 ID
 * @param orderId        주문 ID
 * @param status         배송 상태 (lowercase: preparing/shipping/delivered)
 * @param carrier        택배사명 (nullable — preparing 시 null)
 * @param trackingNumber 운송장 번호 (nullable — preparing 시 null)
 * @param shippedAt      배송 시작 시각 (nullable — preparing 시 null)
 * @param deliveredAt    배송 완료 시각 (nullable — delivered 아니면 null, 정합3)
 * @param items          배송 항목 목록
 */
public record ShipmentResponse(
        long shipmentId,
        long orderId,
        String status,
        String carrier,
        String trackingNumber,
        Instant shippedAt,
        Instant deliveredAt,
        List<ShipmentItemResponse> items
) {
}
