package com.shop.shop.product.dto;

import com.shop.shop.product.domain.ProductStatus;

import java.math.BigDecimal;

/**
 * 공개 상품 목록 페이지 쿼리용 내부 projection record.
 *
 * <p>JPQL {@code new ...ProductSummaryProjection(...)} 생성자 표현식 대상.
 * hasPurchasableVariant는 DB SUM(CASE ...) 결과를 long으로 받아 0 초과 여부로 boolean 판정.
 *
 * <p>이 record는 product.dto 내부 전용이며 product 모듈 밖으로 노출하지 않는다.
 * Entity(Product 등)를 직접 보유하지 않는다.
 */
public record ProductSummaryProjection(
        long productId,
        String name,
        BigDecimal displayPrice,
        Long categoryId,
        String categoryName,
        ProductStatus status,
        long purchasableVariantCount
) {

    /**
     * 구매 가능 활성 variant가 존재하는지 여부.
     *
     * @return purchasableVariantCount > 0
     */
    public boolean hasPurchasableVariant() {
        return purchasableVariantCount > 0;
    }
}
