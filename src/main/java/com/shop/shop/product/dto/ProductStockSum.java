package com.shop.shop.product.dto;

/**
 * 상품별 재고 합계 projection DTO (읽기 전용).
 *
 * <p>ProductVariantRepository 재고 합계 쿼리 결과를 담는다.
 * 상품에 variant가 없으면 totalStock = 0.
 *
 * @param productId  상품 ID
 * @param totalStock 해당 상품 모든 variant의 stock 합계
 */
public record ProductStockSum(
        long productId,
        long totalStock
) {
}
