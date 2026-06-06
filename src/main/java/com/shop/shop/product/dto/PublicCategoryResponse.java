package com.shop.shop.product.dto;

import com.shop.shop.product.domain.Category;

/**
 * 공개 카테고리 응답 DTO (상세 상품용 최소 필드).
 *
 * <p>상품 상세 응답의 category 필드에 사용한다.
 * 필터 목록용 카테고리는 기존 CategoryResponse를 facade 경유로 재사용한다.
 */
public record PublicCategoryResponse(
        Long categoryId,
        String name
) {

    /**
     * Category Entity → PublicCategoryResponse 변환.
     *
     * @param category Category Entity
     * @return PublicCategoryResponse DTO
     */
    public static PublicCategoryResponse from(Category category) {
        if (category == null) {
            return null;
        }
        return new PublicCategoryResponse(category.getId(), category.getName());
    }
}
