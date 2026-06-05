package com.shop.shop.product.dto;

import com.shop.shop.product.domain.OptionValue;

/**
 * 옵션값 응답 DTO.
 *
 * <p>Entity({@link OptionValue}) 직접 노출 금지 — {@link #from(OptionValue)} 정적 팩토리로 변환.
 */
public record OptionValueResponse(
        long optionValueId,
        long optionId,
        String value
) {

    /**
     * OptionValue Entity → OptionValueResponse DTO 변환.
     *
     * @param optionValue OptionValue Entity
     * @return OptionValueResponse DTO
     */
    public static OptionValueResponse from(OptionValue optionValue) {
        return new OptionValueResponse(
                optionValue.getId(),
                optionValue.getOption().getId(),
                optionValue.getValue()
        );
    }
}
