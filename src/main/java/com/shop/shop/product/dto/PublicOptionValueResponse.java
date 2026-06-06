package com.shop.shop.product.dto;

import com.shop.shop.product.domain.OptionValue;

/**
 * 공개 옵션값 응답 DTO.
 */
public record PublicOptionValueResponse(
        long optionValueId,
        String value
) {

    /**
     * OptionValue Entity → PublicOptionValueResponse 변환.
     *
     * @param optionValue OptionValue Entity
     * @return PublicOptionValueResponse DTO
     */
    public static PublicOptionValueResponse from(OptionValue optionValue) {
        return new PublicOptionValueResponse(optionValue.getId(), optionValue.getValue());
    }
}
