package com.shop.shop.product.dto;

import java.math.BigDecimal;

/**
 * 공개 상품 목록 응답 DTO (요약).
 *
 * <p>basePrice·ownerId·storageKey·sku·Entity 미노출.
 * 공개 가격은 displayPrice 단일 필드.
 * 이미지 URL은 AssetUrlResolver로 합성된 primaryImageUrl만.
 */
public record PublicProductSummaryResponse(
        long productId,
        String name,
        BigDecimal displayPrice,
        Long categoryId,
        String categoryName,
        String primaryImageUrl,
        boolean soldOut
) {
}
