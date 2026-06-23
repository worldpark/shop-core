package com.shop.shop.product.dto;

import com.shop.shop.product.domain.ProductStatus;

import java.math.BigDecimal;

/**
 * 색인 스냅샷 쿼리용 내부 projection record.
 *
 * <p>JPQL {@code new ...ProductSearchSnapshotProjection(...)} 생성자 표현식 대상.
 * {@code displayPrice}·{@code purchasableVariantCount} 집계 식은
 * {@link ProductSummaryProjection}(공개 목록)과 **문자 그대로 동일**하여 검색-목록 일관성을 보장한다.
 *
 * <p>이 record는 product.dto 내부 전용이며 product 모듈 밖으로 노출하지 않는다.
 * Entity(Product 등)를 직접 보유하지 않는다(DTO 변환 필수 원칙).
 *
 * <p>description은 공개 목록 투영({@link ProductSummaryProjection})에는 없지만
 * 색인 문서에는 필요하므로 SELECT·GROUP BY에 추가한다.
 *
 * @param productId              상품 PK
 * @param name                   상품명
 * @param description            상품 설명 (nullable)
 * @param categoryId             카테고리 ID (nullable)
 * @param categoryName           카테고리명 (nullable)
 * @param status                 상품 상태
 * @param displayPrice           COALESCE(MIN 활성 variant price, basePrice)
 * @param purchasableVariantCount 활성 AND stock>0 개수
 */
public record ProductSearchSnapshotProjection(
        long productId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        ProductStatus status,
        BigDecimal displayPrice,
        long purchasableVariantCount
) {
}
