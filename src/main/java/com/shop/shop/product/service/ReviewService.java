package com.shop.shop.product.service;

import com.shop.shop.common.exception.DuplicateReviewException;
import com.shop.shop.common.exception.ReviewNotFoundException;
import com.shop.shop.common.exception.ReviewNotPurchasedException;
import com.shop.shop.common.exception.ReviewTargetNotFoundException;
import com.shop.shop.common.exception.ReviewableProductMissingException;
import com.shop.shop.product.domain.Review;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import com.shop.shop.product.spi.ReviewerDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 리뷰 도메인 서비스.
 *
 * <p>작성/수정/삭제/목록·집계 도메인 로직 담당.
 * Repository는 이 클래스에서만 호출한다.
 * 모듈 경계 통신은 PurchaseVerificationPort(spi)·ReviewerDirectory(spi)를 통한다.
 *
 * <p>내부 결과 record(ReviewResult/ReviewSummaryResult)를 반환 — Entity 미노출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final PurchaseVerificationPort purchaseVerificationPort;
    private final ReviewerDirectory reviewerDirectory;
    private final ReviewDtoMapper reviewDtoMapper;

    /**
     * 리뷰 작성.
     *
     * <p>검증 순서 (plan §2.3):
     * <ol>
     *   <li>PurchaseVerificationPort.verify(orderItemId, userId)</li>
     *   <li>ownedAndExists=false → ReviewTargetNotFoundException(404)</li>
     *   <li>delivered=false → ReviewNotPurchasedException(400)</li>
     *   <li>productId=null → ReviewableProductMissingException(400)</li>
     *   <li>existsByOrderItemId 선검사 → DuplicateReviewException(409, best-effort)</li>
     *   <li>save → DataIntegrityViolationException catch → DuplicateReviewException(409, SSOT)</li>
     * </ol>
     *
     * @param userId      작성자 userId
     * @param orderItemId 주문 항목 ID
     * @param rating      평점 (1~5)
     * @param content     내용 (nullable)
     * @return 작성 결과 record
     */
    @Transactional
    public ReviewResult create(long userId, long orderItemId, int rating, String content) {
        PurchaseVerificationPort.PurchaseVerification v =
                purchaseVerificationPort.verify(orderItemId, userId);

        if (!v.ownedAndExists()) {
            throw new ReviewTargetNotFoundException();
        }
        if (!v.delivered()) {
            throw new ReviewNotPurchasedException();
        }
        if (v.productId() == null) {
            throw new ReviewableProductMissingException();
        }

        // 선검사 (best-effort — TOCTOU 경합도 UNIQUE가 최종 차단)
        if (reviewRepository.existsByOrderItemId(orderItemId)) {
            throw new DuplicateReviewException();
        }

        try {
            Review review = reviewRepository.save(
                    Review.create(v.productId(), userId, orderItemId, rating, content));
            log.info("리뷰 작성: userId={}, reviewId={}, productId={}", userId, review.getId(), review.getProductId());
            return new ReviewResult(review.getId(), review.getProductId());
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateReviewException();
        }
    }

    /**
     * 리뷰 수정 (본인만).
     *
     * <p>findByIdAndUserId empty → ReviewNotFoundException(404, 존재 은닉).
     * rating/content만 변경 — productId/userId/orderItemId/createdAt 불변.
     *
     * @param userId   작성자 userId
     * @param reviewId 수정할 리뷰 ID
     * @param rating   새 평점 (1~5)
     * @param content  새 내용 (nullable)
     * @return 수정 결과 record
     */
    @Transactional
    public ReviewResult edit(long userId, long reviewId, int rating, String content) {
        Review review = reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(ReviewNotFoundException::new);

        review.edit(rating, content);
        log.info("리뷰 수정: userId={}, reviewId={}, productId={}", userId, review.getId(), review.getProductId());
        return new ReviewResult(review.getId(), review.getProductId());
    }

    /**
     * 리뷰 삭제 (본인만, 물리 삭제).
     *
     * <p>findByIdAndUserId empty → ReviewNotFoundException(404, 존재 은닉).
     * 삭제 후 같은 order_item 재작성 허용(UNIQUE 해제).
     *
     * @param userId   작성자 userId
     * @param reviewId 삭제할 리뷰 ID
     * @return 삭제된 리뷰의 productId (redirect용)
     */
    @Transactional
    public long delete(long userId, long reviewId) {
        Review review = reviewRepository.findByIdAndUserId(reviewId, userId)
                .orElseThrow(ReviewNotFoundException::new);

        long productId = review.getProductId();
        reviewRepository.delete(review);
        log.info("리뷰 삭제: userId={}, reviewId={}, productId={}", userId, reviewId, productId);
        return productId;
    }

    /**
     * 상품 리뷰 목록 + 집계 조회 (공개 — 비로그인 가능).
     *
     * <p>페이지 조회 + avgRating + count + 작성자 마스킹 표시명(IN 배치 1회).
     * averageRating: 소수 1자리 HALF_UP. 0건이면 null.
     *
     * @param productId 상품 ID
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 리뷰 목록 + 집계 결과
     */
    @Transactional(readOnly = true)
    public ReviewSummaryResult getProductReviews(long productId, int page, int size) {
        Page<Review> reviewPage = reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(
                productId, PageRequest.of(page, size));

        Double avgRaw = reviewRepository.avgRatingByProductId(productId);
        long count = reviewRepository.countByProductId(productId);

        Double averageRating = null;
        if (avgRaw != null) {
            averageRating = BigDecimal.valueOf(avgRaw)
                    .setScale(1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // 작성자 표시명 IN 배치 1회 조회
        Set<Long> userIds = reviewPage.getContent().stream()
                .map(Review::getUserId)
                .collect(Collectors.toSet());

        Map<Long, String> displayNames = userIds.isEmpty()
                ? Map.of()
                : reviewerDirectory.maskedDisplayNamesByUserId(userIds);

        List<ReviewRow> rows = reviewPage.getContent().stream()
                .map(r -> reviewDtoMapper.toReviewRow(r, displayNames))
                .toList();

        return new ReviewSummaryResult(
                averageRating,
                count,
                reviewPage.getNumber(),
                reviewPage.getSize(),
                reviewPage.getTotalElements(),
                reviewPage.getTotalPages(),
                rows
        );
    }

    // =========================================================
    // 내부 결과 record — Entity 미노출
    // =========================================================

    /**
     * 리뷰 작성/수정 결과 (reviewId + productId).
     */
    public record ReviewResult(long reviewId, long productId) {}

    /**
     * 리뷰 목록 행 (표시명 마스킹 포함).
     */
    public record ReviewRow(
            long reviewId,
            long productId,
            String authorDisplayName,
            int rating,
            String content,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * 리뷰 목록 + 집계 결과.
     */
    public record ReviewSummaryResult(
            Double averageRating,
            long reviewCount,
            int page,
            int size,
            long totalElements,
            int totalPages,
            List<ReviewRow> rows
    ) {}
}
