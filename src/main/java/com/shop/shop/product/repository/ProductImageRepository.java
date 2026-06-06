package com.shop.shop.product.repository;

import com.shop.shop.product.domain.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 상품 이미지 JPA Repository.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /**
     * 상품 ID로 이미지 목록 조회. sortOrder ASC, id ASC 정렬.
     *
     * @param productId 상품 ID
     * @return 정렬된 이미지 목록
     */
    List<ProductImage> findByProductIdOrderBySortOrderAscIdAsc(long productId);

    /**
     * 상품의 대표 이미지 조회.
     *
     * @param productId 상품 ID
     * @return 대표 이미지 (없으면 empty)
     */
    Optional<ProductImage> findByProductIdAndIsPrimaryTrue(long productId);

    /**
     * 상품 이미지 개수 조회. 엔티티 전량 로드 없이 count 쿼리로 수행한다.
     *
     * @param productId 상품 ID
     * @return 해당 상품의 이미지 수
     */
    long countByProductId(long productId);
}
