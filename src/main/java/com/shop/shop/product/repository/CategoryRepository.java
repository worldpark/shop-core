package com.shop.shop.product.repository;

import com.shop.shop.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 카테고리 JPA 리포지토리.
 * 비즈니스 로직 없음 — CategoryService에서만 호출.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * slug 존재 여부 확인 (생성 시 중복 검증).
     *
     * @param slug 확인할 slug
     * @return 존재하면 true
     */
    boolean existsBySlug(String slug);

    /**
     * 특정 카테고리를 제외하고 slug 존재 여부 확인 (수정 시 자기 제외 중복 검증).
     *
     * @param slug slug
     * @param id   제외할 카테고리 ID (수정 대상 자신)
     * @return 자신 이외에 동일 slug가 존재하면 true
     */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /**
     * 전체 카테고리 목록 조회 — sortOrder ASC, id ASC 정렬.
     * flat 목록(parentId 포함 DTO로 변환 예정).
     *
     * @return 정렬된 카테고리 목록
     */
    List<Category> findAllByOrderBySortOrderAscIdAsc();
}
