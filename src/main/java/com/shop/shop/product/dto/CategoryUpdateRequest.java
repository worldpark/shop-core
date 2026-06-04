package com.shop.shop.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 카테고리 수정 요청 DTO (REST ADMIN).
 *
 * <p>parentId: nullable — null이면 root로 변경.
 */
public record CategoryUpdateRequest(
        @NotBlank(message = "카테고리명은 필수입니다.")
        String name,

        @NotBlank(message = "slug는 필수입니다.")
        String slug,

        Long parentId,

        int sortOrder
) {
}
