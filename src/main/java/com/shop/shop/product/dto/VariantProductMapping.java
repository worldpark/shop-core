package com.shop.shop.product.dto;

/**
 * variantId ↔ productId 매핑 projection DTO (읽기 전용).
 *
 * <p>ProductVariantRepository.findVariantProductMappingsByProductIdIn 쿼리 결과를 담는다.
 * web 계층에서 variant별 집계를 product 기준으로 병합할 때 사용한다.
 *
 * @param variantId variant ID
 * @param productId 해당 variant가 속한 상품 ID
 */
public record VariantProductMapping(
        long variantId,
        long productId
) {
}
