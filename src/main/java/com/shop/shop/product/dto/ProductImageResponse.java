package com.shop.shop.product.dto;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.ProductImage;

/**
 * 상품 이미지 응답 DTO (REST API 전용).
 *
 * <p>Entity와 절대 파일 시스템 경로를 노출하지 않는다.
 * imageUrl은 {@link AssetUrlResolver}가 합성한다 (storageKey만 DB에 저장).
 */
public record ProductImageResponse(
        long imageId,
        long productId,
        String storageKey,
        String imageUrl,
        int sortOrder,
        boolean primary
) {

    /**
     * ProductImage Entity → ProductImageResponse DTO 변환.
     *
     * @param image   변환 대상 Entity
     * @param resolver URL 합성기
     * @return 변환된 DTO
     */
    public static ProductImageResponse from(ProductImage image, AssetUrlResolver resolver) {
        return new ProductImageResponse(
                image.getId(),
                image.getProduct().getId(),
                image.getStorageKey(),
                resolver.toUrl(image.getStorageKey()),
                image.getSortOrder(),
                image.isPrimary()
        );
    }
}
