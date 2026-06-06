package com.shop.shop.product.service;

import com.shop.shop.common.storage.AssetUrlResolver;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.CategoryResponse;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductSummaryResponse;
import com.shop.shop.product.spi.PublicProductFacade;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PublicProductFacadeImpl 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>sort String → PublicProductSort enum 변환</li>
 *   <li>PublicProductService 위임 및 DTO 변환</li>
 *   <li>listCategories → CategoryService.list() 위임</li>
 *   <li>Entity → DTO 변환 (imageUrl 합성, soldOut/available)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PublicProductFacadeImplTest {

    @Mock
    private PublicProductService publicProductService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private AssetUrlResolver assetUrlResolver;

    private PublicProductFacade facade;

    @BeforeEach
    void setUp() {
        PublicProductDtoMapper dtoMapper = new PublicProductDtoMapper(publicProductService, assetUrlResolver);
        facade = new PublicProductFacadeImpl(publicProductService, categoryService, dtoMapper);
    }

    // =============================================================
    // sort String → enum 변환
    // =============================================================

    @Test
    @DisplayName("listProducts — sort='latest'이면 LATEST로 변환되어 Service에 전달된다")
    void listProducts_sortLatest_callsServiceWithLatest() {
        when(publicProductService.findPublicProducts(any(), any(), any(PublicProductSort.class), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(publicProductService.findPrimaryImages(anyList())).thenReturn(Map.of());

        facade.listProducts(null, null, "latest", 0, 20);

        verify(publicProductService).findPublicProducts(any(), any(), any(PublicProductSort.class), any());
    }

    @Test
    @DisplayName("listProducts — sort='priceAsc'이면 PRICE_ASC로 변환된다")
    void listProducts_sortPriceAsc_callsServiceWithPriceAsc() {
        when(publicProductService.findPublicProducts(any(), any(), any(PublicProductSort.class), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(publicProductService.findPrimaryImages(anyList())).thenReturn(Map.of());

        facade.listProducts(null, null, "priceAsc", 0, 20);

        // sort enum 캡처 검증
        org.mockito.ArgumentCaptor<PublicProductSort> sortCaptor =
                org.mockito.ArgumentCaptor.forClass(PublicProductSort.class);
        verify(publicProductService).findPublicProducts(any(), any(), sortCaptor.capture(), any());
        assertThat(sortCaptor.getValue()).isEqualTo(PublicProductSort.PRICE_ASC);
    }

    @Test
    @DisplayName("listProducts — sort='priceDesc'이면 PRICE_DESC로 변환된다")
    void listProducts_sortPriceDesc_callsServiceWithPriceDesc() {
        when(publicProductService.findPublicProducts(any(), any(), any(PublicProductSort.class), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(publicProductService.findPrimaryImages(anyList())).thenReturn(Map.of());

        facade.listProducts(null, null, "priceDesc", 0, 20);

        org.mockito.ArgumentCaptor<PublicProductSort> sortCaptor =
                org.mockito.ArgumentCaptor.forClass(PublicProductSort.class);
        verify(publicProductService).findPublicProducts(any(), any(), sortCaptor.capture(), any());
        assertThat(sortCaptor.getValue()).isEqualTo(PublicProductSort.PRICE_DESC);
    }

    @Test
    @DisplayName("listProducts — 정의 외 sort값은 LATEST 폴백")
    void listProducts_unknownSort_fallsBackToLatest() {
        when(publicProductService.findPublicProducts(any(), any(), any(PublicProductSort.class), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(publicProductService.findPrimaryImages(anyList())).thenReturn(Map.of());

        facade.listProducts(null, null, "invalid_sort", 0, 20);

        org.mockito.ArgumentCaptor<PublicProductSort> sortCaptor =
                org.mockito.ArgumentCaptor.forClass(PublicProductSort.class);
        verify(publicProductService).findPublicProducts(any(), any(), sortCaptor.capture(), any());
        assertThat(sortCaptor.getValue()).isEqualTo(PublicProductSort.LATEST);
    }

    // =============================================================
    // listProducts DTO 조립
    // =============================================================

    @Test
    @DisplayName("listProducts — projection + 대표이미지 → PublicProductSummaryResponse 조립")
    void listProducts_assemblesSummaryResponse() {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), 2L, "전자", ProductStatus.ON_SALE, 1L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));

        Product product = sampleProduct(1L, ProductStatus.ON_SALE);
        ProductImage image = sampleImage(product, "products/1/img.jpg", true);
        when(publicProductService.findPrimaryImages(List.of(1L))).thenReturn(Map.of(1L, image));
        when(assetUrlResolver.toUrl("products/1/img.jpg"))
                .thenReturn("http://localhost:8080/assets/products/1/img.jpg");
        when(publicProductService.isSoldOut(ProductStatus.ON_SALE, true)).thenReturn(false);

        PublicProductFacade.PublicProductPage result = facade.listProducts(null, null, "latest", 0, 20);

        assertThat(result.content()).hasSize(1);
        PublicProductSummaryResponse summary = result.content().get(0);
        assertThat(summary.productId()).isEqualTo(1L);
        assertThat(summary.primaryImageUrl()).isEqualTo("http://localhost:8080/assets/products/1/img.jpg");
        assertThat(summary.soldOut()).isFalse();
    }

    @Test
    @DisplayName("listProducts — 대표 이미지 없으면 primaryImageUrl=null")
    void listProducts_noPrimaryImage_primaryImageUrlNull() {
        ProductSummaryProjection projection = new ProductSummaryProjection(
                1L, "상품A", new BigDecimal("10000"), null, null, ProductStatus.ON_SALE, 0L);

        when(publicProductService.findPublicProducts(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projection)));
        when(publicProductService.findPrimaryImages(List.of(1L))).thenReturn(Map.of());
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(false);

        PublicProductFacade.PublicProductPage result = facade.listProducts(null, null, "latest", 0, 20);

        assertThat(result.content().get(0).primaryImageUrl()).isNull();
    }

    // =============================================================
    // getProductDetail DTO 조립
    // =============================================================

    @Test
    @DisplayName("getProductDetail — PublicProductService.getPublicProductDetail 위임 후 DTO 변환")
    void getProductDetail_delegatesAndConverts() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        setField(product, "basePrice", new BigDecimal("20000"));

        ProductImage image = sampleImage(product, "products/10/img.jpg", true);
        ProductVariant variant = sampleVariant(product, new BigDecimal("15000"), 5);

        when(publicProductService.getPublicProductDetail(10L))
                .thenReturn(new PublicProductService.DetailAggregate(
                        product, List.of(image), List.of(), List.of(), List.of(variant)));
        when(assetUrlResolver.toUrl("products/10/img.jpg"))
                .thenReturn("http://localhost:8080/assets/products/10/img.jpg");
        when(publicProductService.isSoldOut(any(), any(Boolean.class))).thenReturn(false);
        when(publicProductService.resolveDetailDisplayPrice(any(), anyList()))
                .thenReturn(new BigDecimal("15000"));
        when(publicProductService.isVariantAvailable(ProductStatus.ON_SALE, 5)).thenReturn(true);

        PublicProductDetailResponse detail = facade.getProductDetail(10L);

        assertThat(detail.productId()).isEqualTo(10L);
        assertThat(detail.displayPrice()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(detail.variants().get(0).available()).isTrue();
    }

    @Test
    @DisplayName("getProductDetail — 공개 상세 응답에 ownerId/storageKey/basePrice/sku 필드 없음")
    void getProductDetail_doesNotContainSensitiveFields() {
        var recordComponents = PublicProductDetailResponse.class.getRecordComponents();
        var fieldNames = java.util.Arrays.stream(recordComponents)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(fieldNames).doesNotContain("basePrice", "ownerId", "storageKey", "sku");
    }

    // =============================================================
    // listCategories
    // =============================================================

    @Test
    @DisplayName("listCategories — CategoryService.list() 위임 후 CategoryResponse 변환")
    void listCategories_delegatesToCategoryService() {
        Category category = Category.of("전자기기", "electronics", null, 1);
        setField(category, "id", 1L);
        when(categoryService.list()).thenReturn(List.of(category));

        List<CategoryResponse> result = facade.listCategories();

        verify(categoryService).list();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryId()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("전자기기");
    }

    @Test
    @DisplayName("listCategories — 카테고리 없으면 빈 목록 반환")
    void listCategories_empty_returnsEmptyList() {
        when(categoryService.list()).thenReturn(List.of());

        List<CategoryResponse> result = facade.listCategories();

        assertThat(result).isEmpty();
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
