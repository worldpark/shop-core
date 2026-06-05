package com.shop.shop.product.dto;

import java.util.List;

/**
 * variant 관리 화면 집계 DTO.
 *
 * <p>View 화면에 필요한 상품 참조·옵션 목록·variant 목록을 하나로 묶어 반환한다.
 * facade 반환 타입으로 사용되며, web이 모델에 분해해 담는다.
 */
public record VariantManagementView(
        SellerProductRef product,
        List<ProductOptionResponse> options,
        List<ProductVariantResponse> variants
) {
}
