package com.shop.shop.product.dto;

import java.util.List;

/**
 * 공개 상품 옵션 응답 DTO.
 */
public record PublicProductOptionResponse(
        long optionId,
        String name,
        List<PublicOptionValueResponse> values
) {
}
