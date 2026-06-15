package com.shop.shop.product.dto;

import java.util.List;

/**
 * 상품 리뷰 목록 + 집계 응답 DTO.
 *
 * <p>averageRating: 소수 1자리 HALF_UP 반올림. 0건이면 null.
 * reviewCount: 전체 리뷰 수(집계).
 */
public record ProductReviewSummaryResponse(
        Double averageRating,
        long reviewCount,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<ReviewResponse> reviews
) {
}
