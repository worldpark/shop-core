package com.shop.shop.product.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공개 상품 상세 응답 DTO.
 *
 * <p>basePrice·ownerId·storageKey·Entity 미노출.
 * displayPrice 단일 필드. images/options/variants는 공개 전용 DTO 목록.
 */
public record PublicProductDetailResponse(
        long productId,
        String name,
        String description,
        BigDecimal displayPrice,
        boolean soldOut,
        PublicCategoryResponse category,
        List<PublicProductImageResponse> images,
        List<PublicProductOptionResponse> options,
        List<PublicProductVariantResponse> variants
) {
}
