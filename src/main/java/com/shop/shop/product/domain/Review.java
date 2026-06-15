package com.shop.shop.product.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 상품 리뷰 Entity.
 *
 * <p>테이블: reviews (V1__init_schema.sql)
 * <p>created_at/updated_at: DB 트리거(trg_reviews_set_updated_at) 소유 → BaseEntity 상속(읽기전용 매핑).
 * <p>스칼라 FK 보유 — Product/User/OrderItem Entity 직접 참조 금지(architecture-rule 모듈 경계).
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 및 의도 메서드 {@link #edit} 사용.
 *
 * <p>ADR-007: ddl-auto=validate 정합(V1 reviews 컬럼 구조 일치).
 */
@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 리뷰 대상 상품 ID 스칼라 (NOT NULL).
     * order_item에서 도출(variant_id → product_id) — 요청 바디 미수신(위조 차단).
     */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * 작성자 userId 스칼라 (NOT NULL).
     * review → member Entity 직접 참조 금지.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 주문 항목 ID 스칼라 (NOT NULL, UNIQUE — uq_reviews_order_item_id).
     * 구매 1건당 1리뷰 보장.
     */
    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    /**
     * 평점 (1~5 CHECK 제약).
     * DB 컬럼 타입: smallint(int2) — V1__init_schema.sql 정합을 위해 SMALLINT로 매핑.
     */
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int rating;

    /**
     * 리뷰 내용 (nullable, 최대 1000자 앱 상한).
     */
    @Column(columnDefinition = "text")
    private String content;

    /**
     * 리뷰 작성 정적 팩토리.
     *
     * <p>rating은 도메인 불변식 1~5 범위를 서비스 사전 검증 후 Bean Validation(@Min/@Max)으로 보장하며,
     * 방어적으로 여기서도 검증한다(DB CHECK 정합).
     *
     * @param productId   리뷰 대상 상품 ID (order_item에서 도출)
     * @param userId      작성자 userId
     * @param orderItemId 주문 항목 ID
     * @param rating      평점 (1~5)
     * @param content     리뷰 내용 (nullable)
     * @return 새 Review 인스턴스
     * @throws IllegalStateException rating 범위 위반
     */
    public static Review create(long productId, long userId, long orderItemId, int rating, String content) {
        validateRating(rating);
        Review review = new Review();
        review.productId = productId;
        review.userId = userId;
        review.orderItemId = orderItemId;
        review.rating = rating;
        review.content = content;
        return review;
    }

    /**
     * 리뷰 수정 의도 메서드.
     *
     * <p>rating과 content만 변경한다. productId/userId/orderItemId/createdAt은 불변.
     * updatedAt은 DB 트리거(trg_reviews_set_updated_at)가 자동 갱신한다.
     *
     * @param rating  새 평점 (1~5)
     * @param content 새 내용 (nullable)
     * @throws IllegalStateException rating 범위 위반
     */
    public void edit(int rating, String content) {
        validateRating(rating);
        this.rating = rating;
        this.content = content;
    }

    private static void validateRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new IllegalStateException("rating은 1~5 범위여야 합니다: " + rating);
        }
    }
}
