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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService.delete 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>본인 리뷰 삭제 성공 — productId 반환, 물리 삭제 호출</li>
 *   <li>타인/미존재 리뷰 → ReviewNotFoundException(404)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceDeleteTest {

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
    private static final long PRODUCT_ID = 200L;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, purchaseVerificationPort, reviewerDirectory, reviewDtoMapper);
    }

    @Test
    @DisplayName("본인 리뷰 삭제 성공 — productId 반환")
    void delete_ownReview_returnsProductId() {
        Review review = Review.create(PRODUCT_ID, USER_ID, 100L, 3, "내용");
        setField(review, "id", REVIEW_ID);
        when(reviewRepository.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(review));

        long returnedProductId = reviewService.delete(USER_ID, REVIEW_ID);

        assertThat(returnedProductId).isEqualTo(PRODUCT_ID);
        verify(reviewRepository).delete(review);
    }

    @Test
    @DisplayName("타인 리뷰(empty) → ReviewNotFoundException(404)")
    void delete_otherUserReview_throwsReviewNotFoundException() {
        when(reviewRepository.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.delete(USER_ID, REVIEW_ID))
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
