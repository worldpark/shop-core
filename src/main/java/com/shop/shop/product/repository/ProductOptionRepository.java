package com.shop.shop.product.repository;

import com.shop.shop.product.domain.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ProductOption JPA Repository.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /**
     * 동일 상품 내 옵션명 중복 여부 확인 (V1 불변식).
     */
    boolean existsByProductIdAndName(long productId, String name);

    /**
     * 상품의 옵션 목록 조회 (id 순 정렬).
     */
    List<ProductOption> findByProductIdOrderById(long productId);
}
