package com.shop.shop.product.service;

import com.shop.shop.common.exception.ReviewNotFoundException;
import com.shop.shop.product.domain.Review;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import com.shop.shop.product.spi.ReviewerDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ReviewService.edit 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>본인 리뷰 수정 성공 — rating/content만 변경</li>
 *   <li>타인/미존재 리뷰(findByIdAndUserId empty) → ReviewNotFoundException(404)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceEditTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private PurchaseVerificationPort purchaseVerificationPort;
    @Mock
    private ReviewerDirectory reviewerDirectory;
    @Mock
    private ReviewDtoMapper reviewDtoMapper;

    private ReviewService reviewService;

    private static final long USER_ID = 1L;
    private static final long REVIEW_ID = 50L;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, purchaseVerificationPort, reviewerDirectory, reviewDtoMapper);
    }

    @Test
    @DisplayName("본인 리뷰 수정 성공 — rating/content만 변경")
    void edit_ownReview_success() {
        Review review = Review.create(200L, USER_ID, 100L, 3, "처음 내용");
        setField(review, "id", REVIEW_ID);
        when(reviewRepository.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(review));

        ReviewService.ReviewResult result = reviewService.edit(USER_ID, REVIEW_ID, 5, "수정된 내용");

        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(result.productId()).isEqualTo(200L);
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getContent()).isEqualTo("수정된 내용");
        // 불변 필드 유지
        assertThat(review.getProductId()).isEqualTo(200L);
        assertThat(review.getUserId()).isEqualTo(USER_ID);
        assertThat(review.getOrderItemId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("타인 리뷰(findByIdAndUserId empty) → ReviewNotFoundException(404)")
    void edit_otherUserReview_throwsReviewNotFoundException() {
        when(reviewRepository.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.edit(USER_ID, REVIEW_ID, 5, "수정"))
                .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    @DisplayName("미존재 리뷰(findByIdAndUserId empty) → ReviewNotFoundException(404)")
    void edit_notExistingReview_throwsReviewNotFoundException() {
        when(reviewRepository.findByIdAndUserId(999L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.edit(USER_ID, 999L, 5, "수정"))
                .isInstanceOf(ReviewNotFoundException.class);
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
