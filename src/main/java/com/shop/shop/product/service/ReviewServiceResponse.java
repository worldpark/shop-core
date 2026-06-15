package com.shop.shop.product.service;

import com.shop.shop.product.domain.Review;
import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.dto.ReviewCreateRequest;
import com.shop.shop.product.dto.ReviewResponse;
import com.shop.shop.product.dto.ReviewUpdateRequest;
import com.shop.shop.product.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 REST 응답 조합 서비스 (architecture-rule: REST에서만 사용).
 *
 * <p>ReviewService(도메인 로직)와 REST 컨트롤러 사이에서 요청 DTO 분해, userId 추출, 응답 DTO 조립을 담당한다.
 * View 경로는 ReviewFacadeImpl이 담당 — 이 클래스는 REST 전용.
 *
 * <p>principal: JWT 기반 REST 진입점에서 principal = userId(long).
 */
@Service
@RequiredArgsConstructor
public class ReviewServiceResponse {

    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final ReviewDtoMapper reviewDtoMapper;

    /**
     * 리뷰 작성 REST 응답 조합.
     *
     * @param auth JWT 인증 객체 (principal = userId Long)
     * @param req  작성 요청 DTO
     * @return ReviewResponse
     */
    @Transactional
    public ReviewResponse create(Authentication auth, ReviewCreateRequest req) {
        long userId = (long) auth.getPrincipal();
        ReviewService.ReviewResult result = reviewService.create(
                userId, req.orderItemId(), req.rating(), req.content());

        Review review = reviewRepository.findById(result.reviewId())
                .orElseThrow(() -> new IllegalStateException("저장된 리뷰를 찾을 수 없습니다: " + result.reviewId()));

        return reviewDtoMapper.toReviewResponseFromResult(result, review, "");
    }

    /**
     * 리뷰 수정 REST 응답 조합.
     *
     * @param auth     JWT 인증 객체 (principal = userId Long)
     * @param reviewId 수정할 리뷰 ID
     * @param req      수정 요청 DTO
     * @return ReviewResponse
     */
    @Transactional
    public ReviewResponse update(Authentication auth, long reviewId, ReviewUpdateRequest req) {
        long userId = (long) auth.getPrincipal();
        ReviewService.ReviewResult result = reviewService.edit(userId, reviewId, req.rating(), req.content());

        Review review = reviewRepository.findById(result.reviewId())
                .orElseThrow(() -> new IllegalStateException("수정된 리뷰를 찾을 수 없습니다: " + result.reviewId()));

        return reviewDtoMapper.toReviewResponseFromResult(result, review, "");
    }

    /**
     * 리뷰 삭제 REST.
     *
     * @param auth     JWT 인증 객체 (principal = userId Long)
     * @param reviewId 삭제할 리뷰 ID
     */
    @Transactional
    public void delete(Authentication auth, long reviewId) {
        long userId = (long) auth.getPrincipal();
        reviewService.delete(userId, reviewId);
    }

    /**
     * 상품 리뷰 목록 + 집계 REST 응답 조합 (공개 — auth 무관).
     *
     * @param productId 상품 ID
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return ProductReviewSummaryResponse
     */
    @Transactional(readOnly = true)
    public ProductReviewSummaryResponse getProductReviews(long productId, int page, int size) {
        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(productId, page, size);
        return reviewDtoMapper.toSummaryResponse(result);
    }
}
