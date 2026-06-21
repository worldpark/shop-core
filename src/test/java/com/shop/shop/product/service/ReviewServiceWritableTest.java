package com.shop.shop.product.service;

import com.shop.shop.product.domain.ProductVariant;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService.findWritableOrderItemId 단위 테스트.
 *
 * <p>상품 상세 "리뷰 작성" 진입점 노출 판단 로직 검증:
 * <ul>
 *   <li>상품에 variant가 없으면 포트 조회 없이 null</li>
 *   <li>배송완료 항목 중 첫 미작성 항목 id 반환</li>
 *   <li>모든 배송완료 항목이 이미 리뷰됨 → null</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceWritableTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private PurchaseVerificationPort purchaseVerificationPort;
    @Mock
    private ReviewerDirectory reviewerDirectory;

    private ReviewService reviewService;

    private static final long USER_ID = 1L;
    private static final long PRODUCT_ID = 200L;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(
                reviewRepository, productVariantRepository, purchaseVerificationPort,
                reviewerDirectory, new ReviewDtoMapper());
    }

    @Test
    @DisplayName("variant 없는 상품 → 포트 조회 없이 null")
    void noVariant_returnsNull_withoutPortCall() {
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());

        Long result = reviewService.findWritableOrderItemId(USER_ID, PRODUCT_ID);

        assertThat(result).isNull();
        verify(purchaseVerificationPort, never()).findDeliveredOrderItemIds(eq(USER_ID), anyCollection());
    }

    @Test
    @DisplayName("배송완료 항목 중 첫 미작성 항목 id 반환")
    void returnsFirstUnreviewedDeliveredItem() {
        ProductVariant v = mock(ProductVariant.class);
        lenient().when(v.getId()).thenReturn(11L);
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(v));
        when(purchaseVerificationPort.findDeliveredOrderItemIds(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(100L, 101L));
        when(reviewRepository.existsByOrderItemId(100L)).thenReturn(true);  // 이미 작성됨
        when(reviewRepository.existsByOrderItemId(101L)).thenReturn(false); // 미작성

        Long result = reviewService.findWritableOrderItemId(USER_ID, PRODUCT_ID);

        assertThat(result).isEqualTo(101L);
    }

    @Test
    @DisplayName("모든 배송완료 항목이 이미 리뷰됨 → null")
    void allReviewed_returnsNull() {
        ProductVariant v = mock(ProductVariant.class);
        lenient().when(v.getId()).thenReturn(11L);
        when(productVariantRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(v));
        when(purchaseVerificationPort.findDeliveredOrderItemIds(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(100L));
        when(reviewRepository.existsByOrderItemId(100L)).thenReturn(true);

        Long result = reviewService.findWritableOrderItemId(USER_ID, PRODUCT_ID);

        assertThat(result).isNull();
    }
}
