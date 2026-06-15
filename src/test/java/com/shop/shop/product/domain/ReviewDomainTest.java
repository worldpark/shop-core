package com.shop.shop.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review 도메인 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>create rating 경계(1·5 통과, 0·6 IllegalStateException)</li>
 *   <li>edit이 rating/content만 변경(productId/userId/orderItemId 불변)</li>
 *   <li>null content 허용</li>
 * </ul>
 */
class ReviewDomainTest {

    @Test
    @DisplayName("create — rating 1 통과")
    void create_rating1_success() {
        Review review = Review.create(10L, 20L, 30L, 1, "좋아요");
        assertThat(review.getRating()).isEqualTo(1);
    }

    @Test
    @DisplayName("create — rating 5 통과")
    void create_rating5_success() {
        Review review = Review.create(10L, 20L, 30L, 5, "최고예요");
        assertThat(review.getRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("create — rating 0 → IllegalStateException")
    void create_rating0_throwsIllegalStateException() {
        assertThatThrownBy(() -> Review.create(10L, 20L, 30L, 0, "내용"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rating은 1~5 범위여야 합니다");
    }

    @Test
    @DisplayName("create — rating 6 → IllegalStateException")
    void create_rating6_throwsIllegalStateException() {
        assertThatThrownBy(() -> Review.create(10L, 20L, 30L, 6, "내용"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rating은 1~5 범위여야 합니다");
    }

    @Test
    @DisplayName("create — null content 허용")
    void create_nullContent_success() {
        Review review = Review.create(10L, 20L, 30L, 3, null);
        assertThat(review.getContent()).isNull();
    }

    @Test
    @DisplayName("edit — rating/content만 변경, productId/userId/orderItemId 불변")
    void edit_onlyRatingAndContentChange_immutableFieldsUnchanged() {
        Review review = Review.create(10L, 20L, 30L, 3, "처음 내용");

        review.edit(5, "수정된 내용");

        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getContent()).isEqualTo("수정된 내용");
        assertThat(review.getProductId()).isEqualTo(10L);
        assertThat(review.getUserId()).isEqualTo(20L);
        assertThat(review.getOrderItemId()).isEqualTo(30L);
    }

    @Test
    @DisplayName("edit — rating 범위 위반 → IllegalStateException")
    void edit_invalidRating_throwsIllegalStateException() {
        Review review = Review.create(10L, 20L, 30L, 3, "내용");

        assertThatThrownBy(() -> review.edit(0, "수정"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("edit — null content 허용")
    void edit_nullContent_success() {
        Review review = Review.create(10L, 20L, 30L, 3, "내용");
        review.edit(4, null);
        assertThat(review.getContent()).isNull();
    }
}
