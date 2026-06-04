package com.shop.shop.product.repository;

import com.shop.shop.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 JPA 리포지토리.
 * 비즈니스 로직 없음 — ProductService에서만 호출.
 * 기본 findById/save 활용 (추가 쿼리 불요).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
