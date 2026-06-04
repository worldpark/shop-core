package com.shop.shop.product.dto;

import com.shop.shop.product.domain.Category;

/**
 * 카테고리 조회 응답 DTO.
 *
 * <p>flat 목록 — parentId(nullable)로 트리 구조를 표현한다.
 * Entity({@link Category}) 직접 노출 금지 — {@link #from(Category)} 정적 팩토리로 변환.
 * root 카테고리는 parentId=null.
 */
public record CategoryResponse(
        long categoryId,
        Long parentId,
        String name,
        String slug,
        int sortOrder
) {

    /**
     * Category Entity → CategoryResponse DTO 변환.
     * parent.id로 평탄화(Entity 직접 노출 금지).
     *
     * @param category Category Entity
     * @return CategoryResponse DTO
     */
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.getName(),
                category.getSlug(),
                category.getSortOrder()
        );
    }
}
