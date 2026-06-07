package com.shop.shop.order.dto;

/**
 * 주문 항목 옵션값 응답 DTO.
 *
 * <p>order_item_option_values 스냅샷 조회용.
 */
public record OrderItemOptionValueResponse(
        String optionName,
        String optionValue,
        int sortOrder
) {
}
