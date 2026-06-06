package com.shop.shop.product.service;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * PublicProductServiceResponse 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>projection + 대표이미지 map → SummaryResponse 조립</li>
 *   <li>primaryImageUrl 합성 (AssetUrlResolver)</li>
 *   <li>대표 이미지 없는 상품: primaryImageUrl=null</li>
 *   <li>공개 요약 응답에 basePrice/ownerId/storageKey/sku 미노출</li>
 *   <li>detail DTO 조립 (images/options/variants)</li>
 *   <li>displayPrice/soldOut/available 값 검증</li>
 *   <li>공개 상세 응답에 basePrice/ownerId/storageKey/sku 미노출</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PublicProductServiceResponseTest {

    @Mock
    private PublicProductService publicProductService;

    @Mock
    private AssetUrlResolver assetUrlResolver;

    private PublicProductServiceResponse serviceResponse;

    @BeforeEach
    void setUp() {
        PublicProductDtoMapper dtoMapper = new PublicProductDtoMapper(publicProductService, assetUrlResolver);
        serviceResponse = new PublicProductServiceResponse(publicProductService, dtoMapper);
    }

    // =============================================================
    // 목록 조회 - DTO 조립
    // =============================================================

    @Test
    @DisplayName("list — projection + 대표이미지 → SummaryResponse 조립 (primaryImageUrl 합성)")
    void list_assembleSummaryResponseWithPrimaryImageUrl() {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), 2L, "전자기기", ProductStatus.ON_SALE, 3L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));

        Product product = sampleProduct(1L, ProductStatus.ON_SALE);
        ProductImage image = sampleImage(product, "products/1/main.jpg", true);
        when(publicProductService.findPrimaryImages(List.of(1L)))
                .thenReturn(Map.of(1L, image));
        when(assetUrlResolver.toUrl("products/1/main.jpg"))
                .thenReturn("http://localhost:8080/assets/products/1/main.jpg");
        when(publicProductService.isSoldOut(ProductStatus.ON_SALE, true)).thenReturn(false);

        PageResponse<PublicProductSummaryResponse> result = serviceResponse.list(null, null, "latest", 0, 20);

        assertThat(result.content()).hasSize(1);
        PublicProductSummaryResponse summary = result.content().get(0);
        assertThat(summary.productId()).isEqualTo(1L);
        assertThat(summary.name()).isEqualTo("상품A");
        assertThat(summary.displayPrice()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(summary.categoryId()).isEqualTo(2L);
        assertThat(summary.categoryName()).isEqualTo("전자기기");
        assertThat(summary.primaryImageUrl()).isEqualTo("http://localhost:8080/assets/products/1/main.jpg");
        assertThat(summary.soldOut()).isFalse();
    }

    @Test
    @DisplayName("list — 대표 이미지 없는 상품은 primaryImageUrl=null")
    void list_primaryImageNotFound_primaryImageUrlIsNull() {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), null, null, ProductStatus.ON_SALE, 1L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));
        when(publicProductService.findPrimaryImages(List.of(1L))).thenReturn(Map.of());
        when(publicProductService.isSoldOut(ProductStatus.ON_SALE, true)).thenReturn(false);

        PageResponse<PublicProductSummaryResponse> result = serviceResponse.list(null, null, "latest", 0, 20);

        assertThat(result.content().get(0).primaryImageUrl()).isNull();
    }

    @Test
    @DisplayName("list — SOLD_OUT 상품은 soldOut=true")
    void list_soldOutProduct_soldOutTrue() {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), null, null, ProductStatus.SOLD_OUT, 0L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));
        when(publicProductService.findPrimaryImages(List.of(1L))).thenReturn(Map.of());
        when(publicProductService.isSoldOut(ProductStatus.SOLD_OUT, false)).thenReturn(true);

        PageResponse<PublicProductSummaryResponse> result = serviceResponse.list(null, null, "latest", 0, 20);

        assertThat(result.content().get(0).soldOut()).isTrue();
    }

    @Test
    @DisplayName("list — 공개 요약 응답에 ownerId/storageKey/basePrice/sku 필드가 없다")
    void list_summaryResponse_doesNotContainSensitiveFields() throws Exception {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), null, null, ProductStatus.ON_SALE, 1L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));
        when(publicProductService.findPrimaryImages(anyList())).thenReturn(Map.of());
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(false);

        PageResponse<PublicProductSummaryResponse> result = serviceResponse.list(null, null, "latest", 0, 20);

        // record의 모든 필드 이름을 확인 (basePrice/ownerId/storageKey/sku 없음)
        var recordComponents = PublicProductSummaryResponse.class.getRecordComponents();
        var fieldNames = java.util.Arrays.stream(recordComponents)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(fieldNames).doesNotContain("basePrice", "ownerId", "storageKey", "sku");
        assertThat(fieldNames).contains("productId", "name", "displayPrice", "soldOut", "primaryImageUrl");
    }

    // =============================================================
    // 상세 조회 - DTO 조립
    // =============================================================

    @Test
    @DisplayName("detail — images/options/variants 포함 상세 DTO 조립")
    void detail_assemblesDetailResponse() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        setField(product, "basePrice", new BigDecimal("20000"));

        ProductImage image = sampleImage(product, "products/10/main.jpg", true);
        ProductOption option = sampleOption(product);
        OptionValue optionValue = sampleOptionValue(option);
        ProductVariant variant = sampleVariant(product, new BigDecimal("15000"), 5);

        when(publicProductService.getPublicProductDetail(10L))
                .thenReturn(new PublicProductService.DetailAggregate(
                        product, List.of(image), List.of(option), List.of(optionValue), List.of(variant)));
        when(assetUrlResolver.toUrl("products/10/main.jpg"))
                .thenReturn("http://localhost:8080/assets/products/10/main.jpg");
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(false);
        when(publicProductService.resolveDetailDisplayPrice(any(), anyList()))
                .thenReturn(new BigDecimal("15000"));
        when(publicProductService.isVariantAvailable(ProductStatus.ON_SALE, 5)).thenReturn(true);

        PublicProductDetailResponse detail = serviceResponse.detail(10L);

        assertThat(detail.productId()).isEqualTo(10L);
        assertThat(detail.displayPrice()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(detail.soldOut()).isFalse();
        assertThat(detail.images()).hasSize(1);
        assertThat(detail.images().get(0).imageUrl()).isEqualTo("http://localhost:8080/assets/products/10/main.jpg");
        assertThat(detail.options()).hasSize(1);
        assertThat(detail.variants()).hasSize(1);
        assertThat(detail.variants().get(0).available()).isTrue();
    }

    @Test
    @DisplayName("detail — SOLD_OUT 상품 variant는 재고>0이어도 available=false")
    void detail_soldOutProductVariant_availableFalse() {
        Product product = sampleProduct(10L, ProductStatus.SOLD_OUT);
        setField(product, "basePrice", new BigDecimal("20000"));
        ProductVariant variant = sampleVariant(product, new BigDecimal("15000"), 100);

        when(publicProductService.getPublicProductDetail(10L))
                .thenReturn(new PublicProductService.DetailAggregate(
                        product, List.of(), List.of(), List.of(), List.of(variant)));
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(true);
        when(publicProductService.resolveDetailDisplayPrice(any(), anyList()))
                .thenReturn(new BigDecimal("15000"));
        when(publicProductService.isVariantAvailable(ProductStatus.SOLD_OUT, 100)).thenReturn(false);

        PublicProductDetailResponse detail = serviceResponse.detail(10L);

        assertThat(detail.variants().get(0).available()).isFalse();
        assertThat(detail.soldOut()).isTrue();
    }

    @Test
    @DisplayName("detail — 공개 상세 응답에 basePrice/ownerId/storageKey/sku 필드가 없다")
    void detail_doesNotContainSensitiveFields() {
        var recordComponents = PublicProductDetailResponse.class.getRecordComponents();
        var fieldNames = java.util.Arrays.stream(recordComponents)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(fieldNames).doesNotContain("basePrice", "ownerId", "storageKey", "sku");
        assertThat(fieldNames).contains("productId", "name", "description", "displayPrice", "soldOut");
    }

    @Test
    @DisplayName("detail — imageUrl은 storageKey가 아닌 assetUrlResolver 합성 URL")
    void detail_imageUrlIsResolvedNotStorageKey() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        setField(product, "basePrice", new BigDecimal("20000"));
        ProductImage image = sampleImage(product, "products/10/uuid.jpg", true);

        when(publicProductService.getPublicProductDetail(10L))
                .thenReturn(new PublicProductService.DetailAggregate(
                        product, List.of(image), List.of(), List.of(), List.of()));
        when(assetUrlResolver.toUrl("products/10/uuid.jpg"))
                .thenReturn("http://localhost:8080/assets/products/10/uuid.jpg");
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(false);
        when(publicProductService.resolveDetailDisplayPrice(any(), anyList()))
                .thenReturn(new BigDecimal("20000"));

        PublicProductDetailResponse detail = serviceResponse.detail(10L);

        // imageUrl이 assetUrlResolver가 합성한 URL임을 검증 (storageKey 원본이 아닌 합성 URL)
        assertThat(detail.images().get(0).imageUrl())
                .isEqualTo("http://localhost:8080/assets/products/10/uuid.jpg");
        // imageUrl은 storageKey 그 자체가 아님 (base URL + publicPrefix + "/" + storageKey 형태)
        assertThat(detail.images().get(0).imageUrl())
                .startsWith("http://")
                .contains("/assets/");
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private Product sampleProduct(long productId, ProductStatus status) {
        Product product = Product.create(1L, null, "테스트 상품", "설명", new BigDecimal("20000"));
        setField(product, "id", productId);
        setField(product, "status", status);
        return product;
    }

    private ProductImage sampleImage(Product product, String storageKey, boolean isPrimary) {
        ProductImage image = ProductImage.create(product, storageKey, 0, isPrimary);
        setField(image, "id", 100L);
        return image;
    }

    private ProductOption sampleOption(Product product) {
        ProductOption option = ProductOption.create(product, "색상");
        setField(option, "id", 50L);
        return option;
    }

    private OptionValue sampleOptionValue(ProductOption option) {
        OptionValue ov = OptionValue.create(option, "빨강");
        setField(ov, "id", 200L);
        return ov;
    }

    private ProductVariant sampleVariant(Product product, BigDecimal price, int stock) {
        ProductVariant variant = ProductVariant.create(product, "SKU-001", price, stock, true, Set.of());
        setField(variant, "id", 300L);
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
