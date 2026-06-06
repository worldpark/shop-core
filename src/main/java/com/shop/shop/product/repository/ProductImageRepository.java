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

    /**
     * 여러 상품의 대표 이미지를 IN 배치 조회 (N+1 회피).
     *
     * <p>공개 상품 목록 조회 시 현재 페이지의 productId 목록으로 1쿼리 배치 조회한다.
     * 빈 리스트 방어는 호출 측(PublicProductService)에서 처리한다.
     *
     * @param productIds 조회할 상품 ID 목록
     * @return 대표 이미지 목록 (isPrimary=true인 것만)
     */
    List<ProductImage> findByProductIdInAndIsPrimaryTrue(List<Long> productIds);
}
