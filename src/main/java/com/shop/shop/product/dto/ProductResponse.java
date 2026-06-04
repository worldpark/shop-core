package com.shop.shop.product.dto;

import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 상품 등록/수정 응답 DTO.
 *
 * <p>Entity({@link Product}) 직접 노출 금지 — {@link #from(Product)} 정적 팩토리로 변환.
 * categoryId: nullable(미분류 상품은 null).
 * ownerId: 소유자 확인용 (민감정보 아님).
 * createdAt/updatedAt: Instant (BaseEntity 상속, DB 소유 읽기전용).
 */
public record ProductResponse(
        long productId,
        Long categoryId,
        Long ownerId,
        String name,
        String description,
        BigDecimal basePrice,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Product Entity → ProductResponse DTO 변환.
     * category.id로 평탄화(Entity 직접 노출 금지).
     *
     * @param product Product Entity
     * @return ProductResponse DTO
     */
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getCategory() == null ? null : product.getCategory().getId(),
                product.getOwnerId(),
                product.getName(),
                product.getDescription(),
                product.getBasePrice(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
