package com.shop.shop.product.service;

import com.shop.shop.product.domain.Review;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.spi.PurchaseVerificationPort;
import com.shop.shop.product.spi.ReviewerDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService.getProductReviews 집계 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>AVG 1자리 HALF_UP (예: 4.333 → 4.3)</li>
 *   <li>0건 average=null·count=0</li>
 *   <li>ReviewerDirectory를 mock하여 표시명이 마스킹 문자열로 주입, 응답에 email 원문 없음</li>
 *   <li>ReviewerDirectory.maskedDisplayNamesByUserId가 목록 userId 집합으로 정확히 1회 호출(IN 배치 단언)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReviewSummaryTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private PurchaseVerificationPort purchaseVerificationPort;
    @Mock
    private ReviewerDirectory reviewerDirectory;

    private ReviewDtoMapper reviewDtoMapper;
    private ReviewService reviewService;

    private static final long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() {
        reviewDtoMapper = new ReviewDtoMapper();
        reviewService = new ReviewService(reviewRepository, productVariantRepository, purchaseVerificationPort, reviewerDirectory, reviewDtoMapper);
    }

    @Test
    @DisplayName("AVG 1자리 HALF_UP — 4.333→4.3")
    void getProductReviews_avgRounding_halfUp() {
        // 4, 4, 5 평균 = 4.333...
        Review r1 = buildReview(1L, 1L, 4);
        Review r2 = buildReview(2L, 2L, 4);
        Review r3 = buildReview(3L, 3L, 5);
        List<Review> reviews = List.of(r1, r2, r3);

        when(reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reviews, PageRequest.of(0, 10), 3));
        when(reviewRepository.avgRatingByProductId(PRODUCT_ID)).thenReturn(4.333333);
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(3L);
        when(reviewerDirectory.maskedDisplayNamesByUserId(any())).thenReturn(Map.of(
                1L, "al***", 2L, "bo***", 3L, "ch***"
        ));

        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(PRODUCT_ID, 0, 10);

        assertThat(result.averageRating()).isEqualTo(4.3);
        assertThat(result.reviewCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("0건 — averageRating=null, count=0")
    void getProductReviews_empty_nullAverageAndZeroCount() {
        when(reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(reviewRepository.avgRatingByProductId(PRODUCT_ID)).thenReturn(null);
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(0L);

        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(PRODUCT_ID, 0, 10);

        assertThat(result.averageRating()).isNull();
        assertThat(result.reviewCount()).isEqualTo(0L);
        assertThat(result.rows()).isEmpty();
    }

    @Test
    @DisplayName("ReviewerDirectory가 정확히 1회 IN 배치 호출(N+1 아님)")
    void getProductReviews_reviewerDirectoryCalledOnce() {
        Review r1 = buildReview(1L, 10L, 4);
        Review r2 = buildReview(2L, 20L, 5);
        List<Review> reviews = List.of(r1, r2);

        when(reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reviews, PageRequest.of(0, 10), 2));
        when(reviewRepository.avgRatingByProductId(PRODUCT_ID)).thenReturn(4.5);
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(2L);
        when(reviewerDirectory.maskedDisplayNamesByUserId(any())).thenReturn(Map.of(
                10L, "us***", 20L, "bo***"
        ));

        reviewService.getProductReviews(PRODUCT_ID, 0, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(reviewerDirectory, times(1)).maskedDisplayNamesByUserId(captor.capture());

        Collection<Long> calledWithUserIds = captor.getValue();
        assertThat(calledWithUserIds).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("응답에 email 원문 미포함 — 마스킹 표시명만 포함")
    void getProductReviews_noEmailInResponse() {
        Review r1 = buildReview(1L, 1L, 4);
        String email = "alice@example.com";
        String maskedEmail = "al***";

        when(reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1), PageRequest.of(0, 10), 1));
        when(reviewRepository.avgRatingByProductId(PRODUCT_ID)).thenReturn(4.0);
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
        when(reviewerDirectory.maskedDisplayNamesByUserId(any())).thenReturn(Map.of(1L, maskedEmail));

        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(PRODUCT_ID, 0, 10);

        assertThat(result.rows()).hasSize(1);
        ReviewService.ReviewRow row = result.rows().get(0);
        assertThat(row.authorDisplayName()).isEqualTo(maskedEmail);
        assertThat(row.authorDisplayName()).doesNotContain(email);
        assertThat(row.authorDisplayName()).doesNotContain("@");
    }

    @Test
    @DisplayName("탈퇴 회원(맵에 없는 userId) → '탈퇴회원' 폴백")
    void getProductReviews_withdrawnUser_fallbackDisplayName() {
        Review r1 = buildReview(1L, 999L, 3); // userId=999, 맵에 없음
        when(reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1), PageRequest.of(0, 10), 1));
        when(reviewRepository.avgRatingByProductId(PRODUCT_ID)).thenReturn(3.0);
        when(reviewRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);
        when(reviewerDirectory.maskedDisplayNamesByUserId(Set.of(999L))).thenReturn(Map.of()); // 없음

        ReviewService.ReviewSummaryResult result = reviewService.getProductReviews(PRODUCT_ID, 0, 10);

        assertThat(result.rows().get(0).authorDisplayName()).isEqualTo("탈퇴회원");
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private Review buildReview(long id, long userId, int rating) {
        Review review = Review.create(PRODUCT_ID, userId, 100L + id, rating, "내용");
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
