package com.shop.shop.product.dto;

import java.util.List;

/**
 * 이미지 관리 화면 집계 DTO.
 *
 * <p>View 화면에 필요한 상품 참조·이미지 목록을 하나로 묶어 반환한다.
 * {@link VariantManagementView} 미러링.
 * facade 반환 타입으로 사용되며, web이 모델에 분해해 담는다.
 */
public record ProductImageManagementView(
        SellerProductRef product,
        List<ProductImageResponse> images
) {
}
