package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 항목 응답 DTO.
 *
 * <p>order_items 스냅샷(productName/optionLabel/unitPrice/lineAmount)·optionValues 포함.
 * ownerId/Entity 미노출.
 */
public record OrderItemResponse(
        Long orderItemId,
        Long variantId,
        String productName,
        String optionLabel,
        List<OrderItemOptionValueResponse> optionValues,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineAmount
) {
}
