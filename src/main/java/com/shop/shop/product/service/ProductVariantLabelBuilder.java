package com.shop.shop.product.service;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.ProductVariant;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * product 내부 공통 조립 유틸 (package-private).
 *
 * <p>{@link ProductPurchaseCatalogImpl}와 {@link ProductOrderCatalogImpl}이 공유하는
 * optionLabel 조립 로직을 한 곳에 위치시켜 중복을 제거한다.
 *
 * <p>product 패키지 밖으로 노출하지 않는다.
 */
final class ProductVariantLabelBuilder {

    private ProductVariantLabelBuilder() {}

    /**
     * optionLabel 조립: variant의 optionValues를 OptionValue.id 오름차순으로 정렬 후 " / " 연결.
     *
     * <p>optionValues가 없으면 빈 문자열 반환.
     *
     * @param variant 대상 ProductVariant
     * @return 조립된 optionLabel 문자열
     */
    static String buildOptionLabel(ProductVariant variant) {
        return variant.getOptionValues().stream()
                .sorted(Comparator.comparing(OptionValue::getId))
                .map(OptionValue::getValue)
                .collect(Collectors.joining(" / "));
    }
}
