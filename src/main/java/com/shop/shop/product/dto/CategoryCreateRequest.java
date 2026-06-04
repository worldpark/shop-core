package com.shop.shop.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 카테고리 생성 요청 DTO (REST ADMIN).
 *
 * <p>parentId: nullable — null이면 root 카테고리.
 * sortOrder: 기본값 0 (Integer.MIN_VALUE가 아닌 0 — 미입력 시 0으로 처리).
 */
public record CategoryCreateRequest(
        @NotBlank(message = "카테고리명은 필수입니다.")
        String name,

        @NotBlank(message = "slug는 필수입니다.")
        String slug,

        Long parentId,

        int sortOrder
) {
}
