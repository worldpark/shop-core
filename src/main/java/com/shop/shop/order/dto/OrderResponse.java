package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 주문 상세 응답 DTO.
 *
 * <p>주문 스냅샷(항목/배송지/금액) 포함. ownerId(userId)/Entity 미노출.
 *
 * <p>020에서 shipments 필드 추가(정합4). 소비자 주문 상세에 배송 목록(carrier/trackingNumber/shippedAt 포함)을
 * 표시하기 위해 추가한다. seller_id 등 내부 필드는 노출 금지.
 */
public record OrderResponse(
        Long orderId,
        String orderNumber,
        String status,
        List<OrderItemResponse> items,
        BigDecimal itemsAmount,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal finalAmount,
        ShippingAddressResponse shippingAddress,
        Instant createdAt,
        List<ShipmentResponse> shipments
) {
}
