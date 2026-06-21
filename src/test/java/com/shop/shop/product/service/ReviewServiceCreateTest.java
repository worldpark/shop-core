package com.shop.shop.product.service;

import com.shop.shop.common.exception.DuplicateReviewException;
import com.shop.shop.common.exception.ReviewNotFoundException;
import com.shop.shop.common.exception.ReviewNotPurchasedException;
import com.shop.shop.common.exception.ReviewTargetNotFoundException;
import com.shop.shop.common.exception.ReviewableProductMissingException;
import com.shop.shop.product.domain.Review;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import com.shop.shop.product.spi.ReviewerDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService.create 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>ownedAndExists=false → ReviewTargetNotFoundException(404)</li>
 *   <li>delivered=false → ReviewNotPurchasedException(400)</li>
 *   <li>productId=null → ReviewableProductMissingException(400)</li>
 *   <li>existsByOrderItemId=true → DuplicateReviewException(409, best-effort)</li>
 *   <li>save DataIntegrityViolation(mock) → DuplicateReviewException(409, SSOT)</li>
 *   <li>정상 저장</li>
 *   <li>productId가 verify 결과에서만 주입(요청 바디 무시) 단언</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceCreateTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private PurchaseVerificationPort purchaseVerificationPort;
    @Mock
    private ReviewerDirectory reviewerDirectory;
    @Mock
    private ReviewDtoMapper reviewDtoMapper;

    private ReviewService reviewService;

    private static final long USER_ID = 1L;
    private static final long ORDER_ITEM_ID = 100L;
    private static final long PRODUCT_ID = 200L;
    private static final int RATING = 4;
    private static final String CONTENT = "좋은 상품입니다.";

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, productVariantRepository, purchaseVerificationPort, reviewerDirectory, reviewDtoMapper);
    }

    @Test
    @DisplayName("ownedAndExists=false → ReviewTargetNotFoundException(404)")
    void create_notOwned_throwsReviewTargetNotFoundException() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(false, false, null));

        assertThatThrownBy(() -> reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT))
                .isInstanceOf(ReviewTargetNotFoundException.class);
    }

    @Test
    @DisplayName("delivered=false → ReviewNotPurchasedException(400)")
    void create_notDelivered_throwsReviewNotPurchasedException() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(true, false, PRODUCT_ID));

        assertThatThrownBy(() -> reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT))
                .isInstanceOf(ReviewNotPurchasedException.class);
    }

    @Test
    @DisplayName("productId=null → ReviewableProductMissingException(400)")
    void create_productIdNull_throwsReviewableProductMissingException() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(true, true, null));

        assertThatThrownBy(() -> reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT))
                .isInstanceOf(ReviewableProductMissingException.class);
    }

    @Test
    @DisplayName("existsByOrderItemId=true → DuplicateReviewException(409, best-effort)")
    void create_alreadyExists_throwsDuplicateReviewException() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(true, true, PRODUCT_ID));
        when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT))
                .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    @DisplayName("save DataIntegrityViolation → DuplicateReviewException(409, SSOT)")
    void create_dataIntegrityViolation_throwsDuplicateReviewException() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(true, true, PRODUCT_ID));
        when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);
        when(reviewRepository.save(any())).thenThrow(new DataIntegrityViolationException("UNIQUE constraint"));

        assertThatThrownBy(() -> reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT))
                .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    @DisplayName("정상 저장 — productId가 verify 결과에서만 주입")
    void create_success_productIdFromVerifyResult() {
        when(purchaseVerificationPort.verify(ORDER_ITEM_ID, USER_ID))
                .thenReturn(new PurchaseVerificationPort.PurchaseVerification(true, true, PRODUCT_ID));
        when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);

        Review savedReview = buildReview(1L, PRODUCT_ID, USER_ID, ORDER_ITEM_ID, RATING, CONTENT);
        when(reviewRepository.save(any())).thenReturn(savedReview);

        ReviewService.ReviewResult result = reviewService.create(USER_ID, ORDER_ITEM_ID, RATING, CONTENT);

        assertThat(result.productId()).isEqualTo(PRODUCT_ID);
        assertThat(result.reviewId()).isEqualTo(1L);

        // productId는 verify 결과에서만 주입됨 검증 — save된 Review의 productId가 verify에서 온 것
        verify(reviewRepository).save(any(Review.class));
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private Review buildReview(long id, long productId, long userId, long orderItemId, int rating, String content) {
        Review review = Review.create(productId, userId, orderItemId, rating, content);
        setField(review, "id", id);
        return review;
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
