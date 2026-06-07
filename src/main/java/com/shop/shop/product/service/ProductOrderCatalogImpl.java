package com.shop.shop.product.service;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ProductOrderCatalog} 구현체 (package-private).
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * order는 인터페이스({@link ProductOrderCatalog})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>ProductVariantRepository.findByIdIn(@EntityGraph product,optionValues) 재사용</li>
 *   <li>optionLabel 조립: {@link ProductVariantLabelBuilder}(product 내부 공통 헬퍼)를 통해
 *       {@link ProductPurchaseCatalogImpl}과 동일 로직을 공유한다(중복 구현 금지)</li>
 *   <li>optionValues 조립: OptionValue → OrderOptionValue(optionName, optionValue, sortOrder)</li>
 *   <li>purchasable 판정: productStatus==ON_SALE && isActive</li>
 *   <li>Entity 노출 금지 — OrderableVariantSnapshot record만 반환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProductOrderCatalogImpl implements ProductOrderCatalog {

    private final ProductVariantRepository productVariantRepository;

    /**
     * {@inheritDoc}
     *
     * <p>존재하는 variantId만 반환한다.
     * order가 누락 variantId를 ProductNotPurchasableForOrderException(409)으로 처리한다.
     */
    @Override
    public List<OrderableVariantSnapshot> getOrderableSnapshots(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return List.of();
        }

        List<ProductVariant> variants = productVariantRepository.findWithOptionsByIdIn(variantIds);

        return variants.stream()
                .map(this::toOrderableVariantSnapshot)
                .toList();
    }

    /**
     * ProductVariant Entity → OrderableVariantSnapshot record 변환.
     *
     * <p>purchasable = (status==ON_SALE && variant.isActive).
     * optionLabel = OptionValue.getId() 오름차순 " / " 조립 (ProductPurchaseCatalogImpl 동일 로직).
     * optionValues = OptionValue → OrderOptionValue(optionName, optionValue, sortOrder 순번).
     * Entity(product/optionValues) 미노출 — record scalar만 반환.
     */
    private OrderableVariantSnapshot toOrderableVariantSnapshot(ProductVariant variant) {
        var product = variant.getProduct();
        boolean purchasable = product.getStatus() == ProductStatus.ON_SALE && variant.isActive();
        String optionLabel = buildOptionLabel(variant);
        String productStatus = product.getStatus().name();

        List<OptionValue> sortedOptionValues = variant.getOptionValues().stream()
                .sorted(Comparator.comparing(OptionValue::getId))
                .toList();

        AtomicInteger sortOrder = new AtomicInteger(0);
        List<OrderOptionValue> orderOptionValues = sortedOptionValues.stream()
                .map(ov -> new OrderOptionValue(
                        ov.getOption().getName(),
                        ov.getValue(),
                        sortOrder.getAndIncrement()
                ))
                .toList();

        return new OrderableVariantSnapshot(
                variant.getId(),
                product.getId(),
                product.getName(),
                optionLabel,
                orderOptionValues,
                variant.getPrice(),
                variant.isActive(),
                variant.getStock(),
                productStatus,
                purchasable
        );
    }

    /**
     * optionLabel 조립 — {@link ProductVariantLabelBuilder#buildOptionLabel(ProductVariant)} 위임.
     *
     * <p>product 내부 공통 헬퍼를 통해 {@link ProductPurchaseCatalogImpl}과 로직을 공유한다.
     * optionValues가 없으면 빈 문자열 반환.
     */
    private String buildOptionLabel(ProductVariant variant) {
        return ProductVariantLabelBuilder.buildOptionLabel(variant);
    }
}
