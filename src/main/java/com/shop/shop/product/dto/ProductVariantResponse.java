package com.shop.shop.product.dto;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.ProductVariant;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 상품 variant 응답 DTO.
 *
 * <p>Entity({@link ProductVariant}) 직접 노출 금지 — {@link #from(ProductVariant)} 정적 팩토리로 변환.
 * optionValueIds: 선택된 옵션값 ID 목록 (id 순 정렬).
 * optionValueLabels: 선택된 옵션값 문자열 목록 (id 순 정렬, 표시용).
 */
public record ProductVariantResponse(
        long variantId,
        String sku,
        BigDecimal price,
        int stock,
        boolean active,
        List<Long> optionValueIds,
        List<String> optionValueLabels
) {

    /**
     * ProductVariant Entity → ProductVariantResponse DTO 변환.
     *
     * @param variant ProductVariant Entity
     * @return ProductVariantResponse DTO
     */
    public static ProductVariantResponse from(ProductVariant variant) {
        List<OptionValue> sorted = variant.getOptionValues().stream()
                .sorted(Comparator.comparingLong(OptionValue::getId))
                .toList();

        List<Long> ids = sorted.stream().map(OptionValue::getId).toList();
        List<String> labels = sorted.stream().map(OptionValue::getValue).toList();

        return new ProductVariantResponse(
                variant.getId(),
                variant.getSku(),
                variant.getPrice(),
                variant.getStock(),
                variant.isActive(),
                ids,
                labels
        );
    }
}
