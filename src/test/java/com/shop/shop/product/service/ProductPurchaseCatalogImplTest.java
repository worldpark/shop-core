package com.shop.shop.product.service;

import com.shop.shop.common.exception.VariantNotPurchasableException;
import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.ProductPurchaseCatalog;
import com.shop.shop.product.spi.ProductPurchaseCatalog.PurchasableVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * ProductPurchaseCatalogImpl 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>purchasable variant 조회 성공</li>
 *   <li>DRAFT/HIDDEN/SOLD_OUT 상품 variant → purchasable=false</li>
 *   <li>비활성 variant → purchasable=false</li>
 *   <li>미존재 variantId → VariantNotPurchasableException(400)</li>
 *   <li>Entity 미노출(record만 반환)</li>
 *   <li>IN 배치 조회 결과 조립</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductPurchaseCatalogImplTest {

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private AssetUrlResolver assetUrlResolver;

    private ProductPurchaseCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ProductPurchaseCatalogImpl(productVariantRepository, productImageRepository, assetUrlResolver);
    }

    // =============================================================
    // getPurchasableVariant — 단건
    // =============================================================

    @Test
    @DisplayName("ON_SALE + active variant → purchasable=true")
    void getPurchasableVariant_onSaleActive_purchasableTrue() {
        ProductVariant variant = sampleVariant(ProductStatus.ON_SALE, true, 5);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.empty());

        PurchasableVariant result = catalog.getPurchasableVariant(1L);

        assertThat(result.purchasable()).isTrue();
        assertThat(result.active()).isTrue();
        assertThat(result.stock()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"DRAFT", "HIDDEN", "SOLD_OUT"})
    @DisplayName("DRAFT/HIDDEN/SOLD_OUT 상품 variant → purchasable=false")
    void getPurchasableVariant_nonSaleStatus_purchasableFalse(ProductStatus status) {
        ProductVariant variant = sampleVariant(status, true, 5);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.empty());

        PurchasableVariant result = catalog.getPurchasableVariant(1L);

        assertThat(result.purchasable()).isFalse();
        assertThat(result.productStatus()).isEqualTo(status.name());
    }

    @Test
    @DisplayName("비활성 variant(isActive=false) → purchasable=false")
    void getPurchasableVariant_inactiveVariant_purchasableFalse() {
        ProductVariant variant = sampleVariant(ProductStatus.ON_SALE, false, 5);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.empty());

        PurchasableVariant result = catalog.getPurchasableVariant(1L);

        assertThat(result.purchasable()).isFalse();
        assertThat(result.active()).isFalse();
    }

    @Test
    @DisplayName("미존재 variantId → VariantNotPurchasableException(400)")
    void getPurchasableVariant_notExisting_throwsVariantNotPurchasableException() {
        when(productVariantRepository.findWithProductById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.getPurchasableVariant(999L))
                .isInstanceOf(VariantNotPurchasableException.class);
    }

    @Test
    @DisplayName("대표 이미지 존재 시 imageUrl 반환")
    void getPurchasableVariant_withImage_returnsImageUrl() {
        ProductVariant variant = sampleVariant(ProductStatus.ON_SALE, true, 5);
        Product product = variant.getProduct();
        ProductImage image = ProductImage.create(product, "products/1/img.jpg", 0, true);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.of(image));
        when(assetUrlResolver.toUrl("products/1/img.jpg"))
                .thenReturn("http://localhost:8080/assets/products/1/img.jpg");

        PurchasableVariant result = catalog.getPurchasableVariant(1L);

        assertThat(result.imageUrl()).isEqualTo("http://localhost:8080/assets/products/1/img.jpg");
    }

    @Test
    @DisplayName("대표 이미지 없으면 imageUrl=null")
    void getPurchasableVariant_noImage_imageUrlNull() {
        ProductVariant variant = sampleVariant(ProductStatus.ON_SALE, true, 5);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.empty());

        PurchasableVariant result = catalog.getPurchasableVariant(1L);

        assertThat(result.imageUrl()).isNull();
    }

    @Test
    @DisplayName("PurchasableVariant는 record — Entity(ProductVariant/Product) 미노출")
    void getPurchasableVariant_returnsRecordNotEntity() {
        ProductVariant variant = sampleVariant(ProductStatus.ON_SALE, true, 5);
        when(productVariantRepository.findWithProductById(1L)).thenReturn(Optional.of(variant));
        when(productImageRepository.findByProductIdAndIsPrimaryTrue(anyLong()))
                .thenReturn(Optional.empty());

        Object result = catalog.getPurchasableVariant(1L);

        assertThat(result).isInstanceOf(PurchasableVariant.class);
        assertThat(result).isNotInstanceOf(ProductVariant.class);
    }

    // =============================================================
    // getPurchasableVariants — IN 배치
    // =============================================================

    @Test
    @DisplayName("IN 배치 조회 — 여러 variant 결과 조립")
    void getPurchasableVariants_multipleVariants_assemblesAll() {
        ProductVariant v1 = sampleVariant(ProductStatus.ON_SALE, true, 10);
        setField(v1, "id", 1L);
        ProductVariant v2 = sampleVariant(ProductStatus.SOLD_OUT, true, 0);
        setField(v2, "id", 2L);

        when(productVariantRepository.findByIdIn(any(Collection.class))).thenReturn(List.of(v1, v2));
        when(productImageRepository.findByProductIdInAndIsPrimaryTrue(any())).thenReturn(List.of());

        List<PurchasableVariant> result = catalog.getPurchasableVariants(List.of(1L, 2L));

        assertThat(result).hasSize(2);
        PurchasableVariant pv1 = result.stream().filter(v -> v.variantId() == 1L).findFirst().orElseThrow();
        PurchasableVariant pv2 = result.stream().filter(v -> v.variantId() == 2L).findFirst().orElseThrow();
        assertThat(pv1.purchasable()).isTrue();
        assertThat(pv2.purchasable()).isFalse(); // SOLD_OUT
    }

    @Test
    @DisplayName("IN 배치 조회 — 빈 컬렉션 → 빈 목록 반환")
    void getPurchasableVariants_emptyIds_returnsEmptyList() {
        List<PurchasableVariant> result = catalog.getPurchasableVariants(List.of());

        assertThat(result).isEmpty();
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private ProductVariant sampleVariant(ProductStatus status, boolean active, int stock) {
        Product product = Product.create(1L, null, "테스트 상품", "설명", new BigDecimal("10000"));
        setField(product, "id", 1L);
        setField(product, "status", status);

        ProductVariant variant = ProductVariant.create(product, "SKU-001", new BigDecimal("9000"), stock, active,
                Set.of());
        setField(variant, "id", 1L);
        return variant;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }
}
