package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.dto.PublicCategoryResponse;
import com.shop.shop.product.dto.PublicOptionValueResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductImageResponse;
import com.shop.shop.product.dto.PublicProductOptionResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.dto.PublicProductVariantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 공개 상품 Entity/Projection → 공개 응답 DTO 변환 매퍼 (package-private).
 *
 * <p>PublicProductServiceResponse 및 PublicProductFacadeImpl이 공유하는
 * DTO 변환 로직을 단일 책임 컴포넌트로 추출해 중복을 제거한다.
 *
 * <p>공개 비노출 규칙(soldOut/available/displayPrice/imageUrl) 적용은 이 컴포넌트 한 곳에서만 수행한다.
 * product 모듈 밖(web 등)에서 직접 참조하지 않는다 (package-private 클래스).
 */
@Component
@RequiredArgsConstructor
class PublicProductDtoMapper {

    private final PublicProductService publicProductService;
    private final AssetUrlResolver assetUrlResolver;

    /**
     * 목록 projection + 대표이미지 맵 → PublicProductSummaryResponse 변환.
     */
    PublicProductSummaryResponse toSummaryResponse(
            ProductSummaryProjection projection, Map<Long, ProductImage> primaryImageMap) {

        boolean soldOut = publicProductService.isSoldOut(
                projection.status(), projection.hasPurchasableVariant());

        String primaryImageUrl = null;
        ProductImage primaryImage = primaryImageMap.get(projection.productId());
        if (primaryImage != null) {
            primaryImageUrl = assetUrlResolver.toUrl(primaryImage.getStorageKey());
        }

        return new PublicProductSummaryResponse(
                projection.productId(),
                projection.name(),
                projection.displayPrice(),
                projection.categoryId(),
                projection.categoryName(),
                primaryImageUrl,
                soldOut
        );
    }

    /**
     * DetailAggregate → PublicProductDetailResponse 변환.
     */
    PublicProductDetailResponse toDetailResponse(PublicProductService.DetailAggregate aggregate) {
        var product = aggregate.product();
        var activeVariants = aggregate.activeVariants();

        boolean soldOut = publicProductService.isSoldOut(
                product.getStatus(),
                activeVariants.stream().anyMatch(v -> v.getStock() > 0));

        var displayPrice = publicProductService.resolveDetailDisplayPrice(product, activeVariants);

        List<PublicProductImageResponse> images = aggregate.images().stream()
                .map(img -> PublicProductImageResponse.from(img, assetUrlResolver))
                .toList();

        List<PublicProductOptionResponse> options = buildOptionResponses(aggregate.options(), aggregate.optionValues());

        // optionValueId → OptionValue(옵션 로딩됨) 맵: variant 라벨 조립에 사용(variant 측 lazy option 회피).
        Map<Long, OptionValue> optionValueById = aggregate.optionValues().stream()
                .collect(Collectors.toMap(OptionValue::getId, ov -> ov));

        List<PublicProductVariantResponse> variants = activeVariants.stream()
                .map(v -> toVariantResponse(v, product.getStatus(), optionValueById))
                .toList();

        return new PublicProductDetailResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                displayPrice,
                soldOut,
                PublicCategoryResponse.from(product.getCategory()),
                images,
                options,
                variants
        );
    }

    private List<PublicProductOptionResponse> buildOptionResponses(
            List<ProductOption> options, List<OptionValue> allOptionValues) {

        Map<Long, List<OptionValue>> valuesByOptionId = allOptionValues.stream()
                .collect(Collectors.groupingBy(ov -> ov.getOption().getId()));

        return options.stream()
                .map(option -> {
                    List<PublicOptionValueResponse> values = valuesByOptionId
                            .getOrDefault(option.getId(), List.of())
                            .stream()
                            .map(PublicOptionValueResponse::from)
                            .toList();
                    return new PublicProductOptionResponse(option.getId(), option.getName(), values);
                })
                .toList();
    }

    private PublicProductVariantResponse toVariantResponse(
            ProductVariant variant, com.shop.shop.product.domain.ProductStatus productStatus,
            Map<Long, OptionValue> optionValueById) {
        boolean available = publicProductService.isVariantAvailable(productStatus, variant.getStock());
        List<Long> optionValueIds = variant.getOptionValues().stream()
                .map(OptionValue::getId)
                .toList();
        String optionLabel = buildVariantOptionLabel(optionValueIds, optionValueById);
        return new PublicProductVariantResponse(
                variant.getId(),
                variant.getPrice(),
                optionValueIds,
                optionLabel,
                available
        );
    }

    /**
     * variant의 옵션 조합을 "옵션명: 값 / 옵션명: 값" 형태의 사람이 읽는 라벨로 조립한다.
     *
     * <p>옵션 순서(optionId 오름차순)로 정렬해 동일 상품 내 표기를 일관시킨다.
     * 옵션이 없는 variant(optionValueIds 비어 있음)는 빈 문자열을 반환한다.
     * lazy 회피를 위해 옵션이 로딩된 {@code optionValueById} 맵으로만 해석한다.
     */
    private String buildVariantOptionLabel(
            List<Long> optionValueIds, Map<Long, OptionValue> optionValueById) {
        return optionValueIds.stream()
                .map(optionValueById::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ov -> ov.getOption().getId()))
                .map(ov -> ov.getOption().getName() + ": " + ov.getValue())
                .collect(Collectors.joining(" / "));
    }
}
