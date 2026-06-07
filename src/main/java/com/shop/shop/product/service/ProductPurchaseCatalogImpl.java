package com.shop.shop.product.service;

import com.shop.shop.common.exception.VariantNotPurchasableException;
import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.ProductPurchaseCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ProductPurchaseCatalog} 구현체 (package-private).
 *
 * <p>product 내부 비공개 {@code service} 패키지에 배치한다.
 * cart는 인터페이스({@link ProductPurchaseCatalog})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>ProductVariantRepository로 variant·product·optionValues 로딩</li>
 *   <li>ProductImageRepository로 대표 이미지 조회 (IN 배치)</li>
 *   <li>optionLabel 조립 (옵션값 / 옵션값 형태)</li>
 *   <li>purchasable 판정: productStatus==ON_SALE && variant.isActive</li>
 *   <li>Entity 노출 금지 — PurchasableVariant record만 반환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProductPurchaseCatalogImpl implements ProductPurchaseCatalog {

    private final ProductVariantRepository productVariantRepository;
    private final ProductImageRepository productImageRepository;
    private final AssetUrlResolver assetUrlResolver;

    /**
     * {@inheritDoc}
     *
     * <p>미존재 variantId → {@link VariantNotPurchasableException}(400).
     */
    @Override
    public PurchasableVariant getPurchasableVariant(long variantId) {
        ProductVariant variant = productVariantRepository.findWithProductById(variantId)
                .orElseThrow(VariantNotPurchasableException::new);

        Optional<ProductImage> primaryImage = productImageRepository
                .findByProductIdAndIsPrimaryTrue(variant.getProduct().getId());
        String imageUrl = primaryImage.map(img -> assetUrlResolver.toUrl(img.getStorageKey())).orElse(null);

        return toPurchasableVariant(variant, imageUrl);
    }

    /**
     * {@inheritDoc}
     *
     * <p>존재하는 variantId만 반환한다.
     * 대표 이미지는 IN 배치 1회 조회(N+1 회피, PublicProductService.findPrimaryImages 선례).
     */
    @Override
    public List<PurchasableVariant> getPurchasableVariants(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return List.of();
        }

        List<ProductVariant> variants = productVariantRepository.findByIdIn(variantIds);

        List<Long> productIds = variants.stream()
                .map(v -> v.getProduct().getId())
                .distinct()
                .toList();

        Map<Long, String> imageUrlByProductId = productIds.isEmpty()
                ? Map.of()
                : buildImageUrlMap(productIds);

        return variants.stream()
                .map(variant -> {
                    String imageUrl = imageUrlByProductId.get(variant.getProduct().getId());
                    return toPurchasableVariant(variant, imageUrl);
                })
                .toList();
    }

    /**
     * ProductVariant Entity → PurchasableVariant record 변환.
     *
     * <p>purchasable = (status==ON_SALE && variant.isActive).
     * optionLabel = 옵션값들을 " / " 구분자로 조립.
     * Entity(product/optionValues) 미노출 — record scalar만 반환.
     */
    private PurchasableVariant toPurchasableVariant(ProductVariant variant, String imageUrl) {
        var product = variant.getProduct();
        boolean purchasable = product.getStatus() == ProductStatus.ON_SALE && variant.isActive();
        String optionLabel = buildOptionLabel(variant);
        String productStatus = product.getStatus().name();

        return new PurchasableVariant(
                variant.getId(),
                product.getId(),
                product.getName(),
                productStatus,
                optionLabel,
                imageUrl,
                variant.getPrice(),
                variant.isActive(),
                variant.getStock(),
                purchasable
        );
    }

    /**
     * optionLabel 조립 — {@link ProductVariantLabelBuilder#buildOptionLabel(ProductVariant)} 위임.
     *
     * <p>product 내부 공통 헬퍼를 통해 {@link ProductOrderCatalogImpl}과 로직을 공유한다.
     */
    private String buildOptionLabel(ProductVariant variant) {
        return ProductVariantLabelBuilder.buildOptionLabel(variant);
    }

    /**
     * 상품 ID 목록으로 대표 이미지 URL 맵 생성 (IN 배치 조회, N+1 회피).
     */
    private Map<Long, String> buildImageUrlMap(List<Long> productIds) {
        return productImageRepository.findByProductIdInAndIsPrimaryTrue(productIds).stream()
                .collect(Collectors.toMap(
                        img -> img.getProduct().getId(),
                        img -> assetUrlResolver.toUrl(img.getStorageKey()),
                        (a, b) -> a  // 중복 시 첫 번째 유지 (partial unique index로 중복 없어야 함)
                ));
    }
}
