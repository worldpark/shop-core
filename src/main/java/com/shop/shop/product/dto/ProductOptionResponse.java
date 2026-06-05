package com.shop.shop.product.dto;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.ProductOption;

import java.util.List;

/**
 * 상품 옵션 응답 DTO (옵션값 목록 포함).
 *
 * <p>Entity({@link ProductOption}) 직접 노출 금지 — {@link #from(ProductOption, List)} 정적 팩토리로 변환.
 */
public record ProductOptionResponse(
        long optionId,
        String name,
        List<OptionValueResponse> values
) {

    /**
     * ProductOption Entity + 옵션값 목록 → ProductOptionResponse DTO 변환.
     *
     * @param option      ProductOption Entity
     * @param optionValues 해당 옵션의 옵션값 목록 (id 순 정렬)
     * @return ProductOptionResponse DTO
     */
    public static ProductOptionResponse from(ProductOption option, List<OptionValue> optionValues) {
        List<OptionValueResponse> valueResponses = optionValues.stream()
                .map(OptionValueResponse::from)
                .toList();
        return new ProductOptionResponse(option.getId(), option.getName(), valueResponses);
    }
}
