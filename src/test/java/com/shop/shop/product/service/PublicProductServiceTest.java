package com.shop.shop.product.service;

import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductImage;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.search.ProductSearchPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PublicProductService 단위 테스트 (Mockito, repository mock).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>status 화이트리스트 [ON_SALE, SOLD_OUT] 전달</li>
 *   <li>sort별 올바른 repository 메서드 선택 (LATEST/PRICE_ASC/PRICE_DESC)</li>
 *   <li>keyword/categoryId 필터 파라미터 전달</li>
 *   <li>대표이미지 IN 배치 조회 (findByProductIdInAndIsPrimaryTrue)</li>
 *   <li>빈 productIds → 쿼리 생략</li>
 *   <li>soldOut 판정 (SOLD_OUT / ON_SALE+재고없음 / 활성variant없음 → true; ON_SALE+재고있음 → false)</li>
 *   <li>available 판정 (SOLD_OUT 상품 variant → false, ON_SALE+stock>0 → true)</li>
 *   <li>상세: ON_SALE/SOLD_OUT 성공, DRAFT/HIDDEN/미존재 → ProductNotFoundException(404)</li>
 *   <li>상세 활성 variant만 포함 (findByProductIdAndIsActiveTrue 사용)</li>
 *   <li>상세 이미지 정렬 (findByProductIdOrderBySortOrderAscIdAsc)</li>
 *   <li>displayPrice 폴백 (활성 variant 없으면 basePrice)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PublicProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private OptionValueRepository optionValueRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    private PublicProductService service;

    @BeforeEach
    void setUp() {
        // ObjectProvider<ProductSearchPort>: getIfAvailable() → null (ES 미경유, 항상 PG 폴백 경로)
        // lenient()으로 stub해야 헬퍼 판정 테스트 등에서 UnnecessaryStubbingException 방지
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSearchPort> emptySearchPortProvider = mock(ObjectProvider.class);
        lenient().when(emptySearchPortProvider.getIfAvailable()).thenReturn(null);

        service = new PublicProductService(
                productRepository, productImageRepository,
                productOptionRepository, optionValueRepository, productVariantRepository,
                emptySearchPortProvider,
                new SimpleMeterRegistry());
    }

    // =============================================================
    // 목록 조회 — status 화이트리스트
    // =============================================================

    @Test
    @DisplayName("findPublicProducts — status 화이트리스트 [ON_SALE, SOLD_OUT]이 최신순 쿼리에 전달된다")
    void findPublicProducts_latest_passes_public_statuses() {
        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts(null, null, PublicProductSort.LATEST, Pageable.ofSize(20));

        ArgumentCaptor<List> statusCaptor = ArgumentCaptor.forClass(List.class);
        verify(productRepository).findPublicProductsLatest(statusCaptor.capture(), any(), any(), any());
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);
    }

    // =============================================================
    // 정렬별 repository 메서드 선택
    // =============================================================

    @Test
    @DisplayName("findPublicProducts — LATEST sort이면 findPublicProductsLatest 호출")
    void findPublicProducts_latest_calls_latest_repository() {
        when(productRepository.findPublicProductsLatest(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts(null, null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsPriceAsc(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsPriceDesc(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("findPublicProducts — PRICE_ASC sort이면 findPublicProductsPriceAsc 호출")
    void findPublicProducts_priceAsc_calls_priceAsc_repository() {
        when(productRepository.findPublicProductsPriceAsc(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts(null, null, PublicProductSort.PRICE_ASC, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsPriceAsc(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsLatest(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("findPublicProducts — PRICE_DESC sort이면 findPublicProductsPriceDesc 호출")
    void findPublicProducts_priceDesc_calls_priceDesc_repository() {
        when(productRepository.findPublicProductsPriceDesc(anyList(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts(null, null, PublicProductSort.PRICE_DESC, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsPriceDesc(anyList(), any(), any(), any());
        verify(productRepository, never()).findPublicProductsLatest(anyList(), any(), any(), any());
    }

    // =============================================================
    // 필터 파라미터 전달
    // =============================================================

    @Test
    @DisplayName("findPublicProducts — keyword와 categoryId가 repository에 전달된다")
    void findPublicProducts_passes_keyword_and_categoryId() {
        when(productRepository.findPublicProductsLatest(anyList(), eq("키워드"), eq(5L), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("키워드", 5L, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), eq("키워드"), eq(5L), any());
    }

    @Test
    @DisplayName("findPublicProducts — 빈 keyword는 null로 정규화되어 전달된다")
    void findPublicProducts_blank_keyword_normalized_to_null() {
        when(productRepository.findPublicProductsLatest(anyList(), eq(null), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.findPublicProducts("   ", null, PublicProductSort.LATEST, Pageable.ofSize(20));

        verify(productRepository).findPublicProductsLatest(anyList(), eq(null), any(), any());
    }

    // =============================================================
    // 대표 이미지 IN 배치 조회
    // =============================================================

    @Test
    @DisplayName("findPrimaryImages — productId 목록으로 findByProductIdInAndIsPrimaryTrue 호출")
    void findPrimaryImages_calls_findByProductIdInAndIsPrimaryTrue() {
        Product product = sampleProduct(1L, ProductStatus.ON_SALE);
        ProductImage image = sampleImage(product, "products/1/test.jpg", true);
        when(productImageRepository.findByProductIdInAndIsPrimaryTrue(List.of(1L, 2L)))
                .thenReturn(List.of(image));

        Map<Long, ProductImage> result = service.findPrimaryImages(List.of(1L, 2L));

        verify(productImageRepository).findByProductIdInAndIsPrimaryTrue(List.of(1L, 2L));
        assertThat(result).containsKey(1L);
    }

    @Test
    @DisplayName("findPrimaryImages — 빈 리스트이면 쿼리를 호출하지 않고 빈 맵 반환")
    void findPrimaryImages_empty_list_skips_query() {
        Map<Long, ProductImage> result = service.findPrimaryImages(List.of());

        verify(productImageRepository, never()).findByProductIdInAndIsPrimaryTrue(anyList());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findPrimaryImages — null이면 쿼리를 호출하지 않고 빈 맵 반환")
    void findPrimaryImages_null_skips_query() {
        Map<Long, ProductImage> result = service.findPrimaryImages(null);

        verify(productImageRepository, never()).findByProductIdInAndIsPrimaryTrue(anyList());
        assertThat(result).isEmpty();
    }

    // =============================================================
    // soldOut 판정
    // =============================================================

    @Test
    @DisplayName("isSoldOut — SOLD_OUT 상품은 soldOut=true")
    void isSoldOut_soldOutStatus_returnsTrue() {
        assertThat(service.isSoldOut(ProductStatus.SOLD_OUT, false)).isTrue();
        assertThat(service.isSoldOut(ProductStatus.SOLD_OUT, true)).isTrue();
    }

    @Test
    @DisplayName("isSoldOut — ON_SALE + hasPurchasableVariant=false → soldOut=true")
    void isSoldOut_onSale_noPurchasableVariant_returnsTrue() {
        assertThat(service.isSoldOut(ProductStatus.ON_SALE, false)).isTrue();
    }

    @Test
    @DisplayName("isSoldOut — ON_SALE + hasPurchasableVariant=true → soldOut=false")
    void isSoldOut_onSale_hasPurchasableVariant_returnsFalse() {
        assertThat(service.isSoldOut(ProductStatus.ON_SALE, true)).isFalse();
    }

    // =============================================================
    // available 판정
    // =============================================================

    @Test
    @DisplayName("isVariantAvailable — SOLD_OUT 상품 variant는 재고>0이어도 available=false")
    void isVariantAvailable_soldOutProduct_returnsFalse() {
        assertThat(service.isVariantAvailable(ProductStatus.SOLD_OUT, 100)).isFalse();
    }

    @Test
    @DisplayName("isVariantAvailable — ON_SALE + stock>0 → available=true")
    void isVariantAvailable_onSalePositiveStock_returnsTrue() {
        assertThat(service.isVariantAvailable(ProductStatus.ON_SALE, 1)).isTrue();
    }

    @Test
    @DisplayName("isVariantAvailable — ON_SALE + stock=0 → available=false")
    void isVariantAvailable_onSaleZeroStock_returnsFalse() {
        assertThat(service.isVariantAvailable(ProductStatus.ON_SALE, 0)).isFalse();
    }

    // =============================================================
    // displayPrice 폴백
    // =============================================================

    @Test
    @DisplayName("resolveDetailDisplayPrice — 활성 variant 있으면 min(price) 반환")
    void resolveDetailDisplayPrice_returnsMinVariantPrice() {
        Product product = sampleProduct(1L, ProductStatus.ON_SALE);
        setField(product, "basePrice", new BigDecimal("30000"));

        ProductVariant v1 = sampleVariant(product, new BigDecimal("15000"), 5);
        ProductVariant v2 = sampleVariant(product, new BigDecimal("10000"), 3);

        BigDecimal result = service.resolveDetailDisplayPrice(product, List.of(v1, v2));

        assertThat(result).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("resolveDetailDisplayPrice — 활성 variant 없으면 basePrice 폴백")
    void resolveDetailDisplayPrice_fallbackToBasePrice() {
        Product product = sampleProduct(1L, ProductStatus.ON_SALE);
        setField(product, "basePrice", new BigDecimal("50000"));

        BigDecimal result = service.resolveDetailDisplayPrice(product, List.of());

        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }

    // =============================================================
    // 상세 조회
    // =============================================================

    @Test
    @DisplayName("getPublicProductDetail — ON_SALE 상품 상세 조회 성공")
    void getPublicProductDetail_onSale_success() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(10L)).thenReturn(List.of());
        when(optionValueRepository.findByOption_ProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(productVariantRepository.findByProductIdAndIsActiveTrue(10L)).thenReturn(List.of());

        PublicProductService.DetailAggregate aggregate = service.getPublicProductDetail(10L);

        assertThat(aggregate.product()).isEqualTo(product);
    }

    @Test
    @DisplayName("getPublicProductDetail — SOLD_OUT 상품 상세 조회 성공")
    void getPublicProductDetail_soldOut_success() {
        Product product = sampleProduct(10L, ProductStatus.SOLD_OUT);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(10L)).thenReturn(List.of());
        when(optionValueRepository.findByOption_ProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(productVariantRepository.findByProductIdAndIsActiveTrue(10L)).thenReturn(List.of());

        PublicProductService.DetailAggregate aggregate = service.getPublicProductDetail(10L);

        assertThat(aggregate.product().getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("getPublicProductDetail — DRAFT 상품 → ProductNotFoundException(404)")
    void getPublicProductDetail_draft_throwsNotFoundException() {
        Product product = sampleProduct(10L, ProductStatus.DRAFT);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.getPublicProductDetail(10L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("getPublicProductDetail — HIDDEN 상품 → ProductNotFoundException(404)")
    void getPublicProductDetail_hidden_throwsNotFoundException() {
        Product product = sampleProduct(10L, ProductStatus.HIDDEN);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.getPublicProductDetail(10L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("getPublicProductDetail — 미존재 상품 → ProductNotFoundException(404)")
    void getPublicProductDetail_notFound_throwsNotFoundException() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicProductDetail(999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("getPublicProductDetail — findByProductIdAndIsActiveTrue 호출 (활성 variant만 포함)")
    void getPublicProductDetail_callsActiveVariantRepository() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(10L)).thenReturn(List.of());
        when(optionValueRepository.findByOption_ProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(productVariantRepository.findByProductIdAndIsActiveTrue(10L)).thenReturn(List.of());

        service.getPublicProductDetail(10L);

        verify(productVariantRepository).findByProductIdAndIsActiveTrue(10L);
        verify(productVariantRepository, never()).findByProductId(10L);
    }

    @Test
    @DisplayName("getPublicProductDetail — findByProductIdOrderBySortOrderAscIdAsc 호출 (이미지 정렬)")
    void getPublicProductDetail_callsImageRepositoryWithSort() {
        Product product = sampleProduct(10L, ProductStatus.ON_SALE);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(10L)).thenReturn(List.of());
        when(productOptionRepository.findByProductIdOrderById(10L)).thenReturn(List.of());
        when(optionValueRepository.findByOption_ProductIdOrderByIdAsc(10L)).thenReturn(List.of());
        when(productVariantRepository.findByProductIdAndIsActiveTrue(10L)).thenReturn(List.of());

        service.getPublicProductDetail(10L);

        verify(productImageRepository).findByProductIdOrderBySortOrderAscIdAsc(10L);
    }

    // =============================================================
    // PublicProductSort.from 변환
    // =============================================================

    @Test
    @DisplayName("PublicProductSort.from — null이면 LATEST")
    void publicProductSort_from_null_returnsLatest() {
        assertThat(PublicProductSort.from(null)).isEqualTo(PublicProductSort.LATEST);
    }

    @Test
    @DisplayName("PublicProductSort.from — 정의 외 값은 LATEST 폴백")
    void publicProductSort_from_unknown_returnsLatest() {
        assertThat(PublicProductSort.from("unknown")).isEqualTo(PublicProductSort.LATEST);
    }

    @Test
    @DisplayName("PublicProductSort.from — priceAsc → PRICE_ASC")
    void publicProductSort_from_priceAsc() {
        assertThat(PublicProductSort.from("priceAsc")).isEqualTo(PublicProductSort.PRICE_ASC);
    }

    @Test
    @DisplayName("PublicProductSort.from — priceDesc → PRICE_DESC")
    void publicProductSort_from_priceDesc() {
        assertThat(PublicProductSort.from("priceDesc")).isEqualTo(PublicProductSort.PRICE_DESC);
    }

    @Test
    @DisplayName("PublicProductSort.from — latest → LATEST")
    void publicProductSort_from_latest() {
        assertThat(PublicProductSort.from("latest")).isEqualTo(PublicProductSort.LATEST);
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
        ProductVariant variant = ProductVariant.create(product, "SKU-" + price, price, stock, true, java.util.Set.of());
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
