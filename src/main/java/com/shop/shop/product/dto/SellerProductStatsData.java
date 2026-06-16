package com.shop.shop.product.dto;

import java.util.List;
import java.util.Map;

/**
 * 판매자 상품 현황 페이지 조합용 데이터 번들 DTO (읽기 전용).
 *
 * <p>{@code SellerProductFacade.getMyProductStatsData}가 소유 검증 후 반환한다.
 * web 계층이 productId/variantId를 직접 파라미터로 받지 않고, 소유 검증된 데이터만 사용하도록 보장한다.
 *
 * @param products        소유 상품 목록 (SellerProductSummaryView 페이지의 content)
 * @param totalElements   소유 상품 전체 개수 (페이지네이션 totalElements)
 * @param stockByProduct  상품 ID → 재고 합계 맵 (variant 없는 상품은 키 없음 → 0으로 처리)
 * @param variantMappings variantId ↔ productId 매핑 목록
 */
public record SellerProductStatsData(
        List<SellerProductSummaryView> products,
        long totalElements,
        Map<Long, Long> stockByProduct,
        List<VariantProductMapping> variantMappings
) {
}
