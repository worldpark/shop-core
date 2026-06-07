package com.shop.shop.cart.dto;

import java.math.BigDecimal;

/**
 * 장바구니 항목 응답 DTO.
 *
 * <p>stock 수치/ownerId/Entity/storageKey 미포함.
 * stockEnough boolean으로만 재고 상태를 표현한다(공개 API 비노출 규칙).
 * unitPrice/lineAmount는 조회 시점 현재 variant 가격 기준이다.
 */
public record CartItemResponse(
        long cartItemId,
        long variantId,
        long productId,
        String productName,
        String optionLabel,
        String imageUrl,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineAmount,
        boolean available,
        boolean stockEnough
) {
}
