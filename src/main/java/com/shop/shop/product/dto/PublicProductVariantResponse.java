package com.shop.shop.product.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공개 상품 variant 응답 DTO.
 *
 * <p>sku·stock 수치 미노출. available(boolean)만 노출.
 * active 필드 불필요 — 상세에서는 활성 variant만 반환하므로.
 */
public record PublicProductVariantResponse(
        long variantId,
        BigDecimal price,
        List<Long> optionValueIds,
        boolean available
) {
}
