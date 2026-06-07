package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 주문 상세 응답 DTO.
 *
 * <p>주문 스냅샷(항목/배송지/금액) 포함. ownerId(userId)/Entity 미노출.
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
        Instant createdAt
) {
}
