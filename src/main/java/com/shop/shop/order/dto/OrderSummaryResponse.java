package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 목록 요약 응답 DTO.
 *
 * <p>representativeItemName = 첫 번째 항목 productName.
 * itemCount = 주문 항목(라인) 수.
 * ownerId/Entity 미노출.
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        String status,
        String representativeItemName,
        int itemCount,
        BigDecimal finalAmount,
        Instant createdAt
) {
}
