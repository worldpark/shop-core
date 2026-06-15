package com.shop.shop.product.repository;

import com.shop.shop.product.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 리뷰 JPA Repository.
 *
 * <p>비즈니스 로직 없음 — ReviewService에서만 호출.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * 상품 리뷰 목록 최신순 조회 (페이징).
     *
     * <p>최신순: createdAt DESC, id DESC (타이브레이크).
     *
     * @param productId 상품 ID
     * @param pageable  페이지 요청
     * @return 최신순 리뷰 페이지
     */
    Page<Review> findByProductIdOrderByCreatedAtDescIdDesc(long productId, Pageable pageable);

    /**
     * 소유권 검증 포함 리뷰 조회 (수정/삭제 전용).
     *
     * <p>미존재 또는 userId 불일치 모두 empty 반환 → ReviewNotFoundException(404 존재 은닉).
     *
     * @param id     리뷰 ID
     * @param userId 소유자 userId
     * @return 리뷰 (없으면 empty)
     */
    Optional<Review> findByIdAndUserId(long id, long userId);

    /**
     * 주문 항목으로 리뷰 존재 여부 확인 (중복 작성 선검사 보조 — UNIQUE가 최종 방어).
     *
     * @param orderItemId 주문 항목 ID
     * @return 존재하면 true
     */
    boolean existsByOrderItemId(long orderItemId);

    /**
     * 상품 평균 평점 집계.
     *
     * <p>리뷰가 없으면 null 반환.
     *
     * @param pid 상품 ID
     * @return 평균 평점 (없으면 null)
     */
    @Query("select avg(r.rating) from Review r where r.productId = :pid")
    Double avgRatingByProductId(@Param("pid") long pid);

    /**
     * 상품 리뷰 수 집계.
     *
     * @param productId 상품 ID
     * @return 리뷰 수
     */
    long countByProductId(long productId);
}
