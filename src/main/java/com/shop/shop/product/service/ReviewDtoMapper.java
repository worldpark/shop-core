package com.shop.shop.product.service;

import com.shop.shop.product.domain.Review;
import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.dto.ReviewResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 리뷰 내부 record → DTO 변환 컴포넌트 (package-private).
 *
 * <p>Entity·ReviewService 내부 record → 외부 노출 DTO 변환을 담당한다.
 * ReviewService에서만 사용한다.
 */
@Component
class ReviewDtoMapper {

    /**
     * Review Entity + 표시명 맵 → ReviewRow 변환.
     *
     * <p>userId가 맵에 없으면 "탈퇴회원"으로 폴백한다.
     *
     * @param review       Review Entity
     * @param displayNames userId → 마스킹 표시명 맵
     * @return ReviewRow
     */
    ReviewService.ReviewRow toReviewRow(Review review, Map<Long, String> displayNames) {
        String displayName = displayNames.getOrDefault(review.getUserId(), "탈퇴회원");
        return new ReviewService.ReviewRow(
                review.getId(),
                review.getProductId(),
                displayName,
                review.getRating(),
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }

    /**
     * ReviewRow → ReviewResponse DTO 변환.
     *
     * @param row 내부 결과 row
     * @return ReviewResponse
     */
    ReviewResponse toReviewResponse(ReviewService.ReviewRow row) {
        return new ReviewResponse(
                row.reviewId(),
                row.productId(),
                row.authorDisplayName(),
                row.rating(),
                row.content(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    /**
     * ReviewSummaryResult → ProductReviewSummaryResponse DTO 변환.
     *
     * @param result 내부 집계 결과
     * @return ProductReviewSummaryResponse
     */
    ProductReviewSummaryResponse toSummaryResponse(ReviewService.ReviewSummaryResult result) {
        return new ProductReviewSummaryResponse(
                result.averageRating(),
                result.reviewCount(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.rows().stream()
                        .map(this::toReviewResponse)
                        .toList()
        );
    }

    /**
     * ReviewResult → ReviewResponse 변환 (작성/수정 후 단건 응답용).
     *
     * <p>REST 응답은 작성자 표시명 없음(단건 응답 — 목록 배치 조회 불필요).
     * 작성 직후 응답이라 displayName은 의미 없음 → ReviewServiceResponse에서 처리.
     */
    ReviewResponse toReviewResponseFromResult(ReviewService.ReviewResult result, Review review, String authorDisplayName) {
        return new ReviewResponse(
                result.reviewId(),
                result.productId(),
                authorDisplayName,
                review.getRating(),
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
