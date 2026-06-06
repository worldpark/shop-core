package com.shop.shop.product.dto;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.ProductImage;

/**
 * 공개 상품 이미지 응답 DTO.
 *
 * <p>storageKey 미노출. imageUrl은 AssetUrlResolver로 합성한 공개 URL.
 */
public record PublicProductImageResponse(
        long imageId,
        String imageUrl,
        int sortOrder,
        boolean primary
) {

    /**
     * ProductImage Entity + AssetUrlResolver → PublicProductImageResponse 변환.
     *
     * @param image    ProductImage Entity
     * @param resolver URL 합성 컴포넌트
     * @return PublicProductImageResponse DTO
     */
    public static PublicProductImageResponse from(ProductImage image, AssetUrlResolver resolver) {
        return new PublicProductImageResponse(
                image.getId(),
                resolver.toUrl(image.getStorageKey()),
                image.getSortOrder(),
                image.isPrimary()
        );
    }
}
