package com.shop.shop.product.dto;

import java.time.Instant;

/**
 * 리뷰 단건/목록 행 응답 DTO.
 *
 * <p>Entity·email 미노출. 작성자는 마스킹 표시명(authorDisplayName)으로만 노출한다.
 */
public record ReviewResponse(
        long reviewId,
        long productId,
        String authorDisplayName,
        int rating,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
