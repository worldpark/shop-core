package com.shop.shop.product.spi;

import com.shop.shop.product.dto.CategoryResponse;

import java.util.List;

/**
 * 관리자 카테고리 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 AdminCategoryViewController가 product 도메인 내부 Service·Entity를 직접
 * 참조하지 않도록 이 facade를 경유한다. 구현체는 product 내부 {@code service} 패키지에 위치한다.
 *
 * <p>의존 방향: web → product.spi (단방향). product는 web을 참조하지 않는다.
 */
public interface AdminCategoryFacade {

    /**
     * 카테고리 전체 목록 조회.
     * sortOrder ASC, id ASC 정렬.
     *
     * @return 카테고리 목록 DTO (flat)
     */
    List<CategoryResponse> list();

    /**
     * 카테고리 생성.
     *
     * @param name      카테고리명
     * @param slug      URL slug (unique)
     * @param parentId  부모 카테고리 ID (null = root)
     * @param sortOrder 정렬 순서
     */
    void create(String name, String slug, Long parentId, int sortOrder);

    /**
     * 카테고리 삭제.
     *
     * <p>삭제 시 상품의 category_id는 NULL(미분류 전환), 자식 카테고리의 parent_id는 NULL(root 승격).
     *
     * @param categoryId 삭제할 카테고리 ID
     */
    void delete(long categoryId);
}
