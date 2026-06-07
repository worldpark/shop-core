package com.shop.shop.product.service;

import com.shop.shop.product.domain.OptionValue;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductOption;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link ProductOrderCatalogImpl} 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>스냅샷 조립(optionName/optionValue/sortOrder 제공)</li>
 *   <li>DRAFT/HIDDEN/SOLD_OUT/비활성 → purchasable=false</li>
 *   <li>Entity 미노출(record만 반환)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductOrderCatalogImplTest {

    @Mock
    private ProductVariantRepository productVariantRepository;

    private ProductOrderCatalogImpl productOrderCatalogImpl;

    @BeforeEach
    void setUp() {
        productOrderCatalogImpl = new ProductOrderCatalogImpl(productVariantRepository);
    }

    @Test
    @DisplayName("ON_SALE + active → purchasable=true, optionName/optionValue 제공")
    void getOrderableSnapshots_onSaleAndActive_purchasableTrue() {
        ProductVariant variant = buildVariant(ProductStatus.ON_SALE, true, 10, new BigDecimal("1000"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        assertThat(snapshots).hasSize(1);
        OrderableVariantSnapshot snap = snapshots.get(0);
        assertThat(snap.purchasable()).isTrue();
        assertThat(snap.productStatus()).isEqualTo("ON_SALE");
        assertThat(snap.optionValues()).isNotEmpty();
        // optionName/optionValue 확인
        assertThat(snap.optionValues().get(0).optionName()).isEqualTo("색상");
        assertThat(snap.optionValues().get(0).optionValue()).isEqualTo("빨강");
    }

    @Test
    @DisplayName("DRAFT → purchasable=false")
    void getOrderableSnapshots_draftStatus_purchasableFalse() {
        ProductVariant variant = buildVariant(ProductStatus.DRAFT, true, 5, new BigDecimal("500"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        assertThat(snapshots.get(0).purchasable()).isFalse();
    }

    @Test
    @DisplayName("HIDDEN → purchasable=false")
    void getOrderableSnapshots_hiddenStatus_purchasableFalse() {
        ProductVariant variant = buildVariant(ProductStatus.HIDDEN, true, 5, new BigDecimal("500"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        assertThat(snapshots.get(0).purchasable()).isFalse();
    }

    @Test
    @DisplayName("SOLD_OUT → purchasable=false")
    void getOrderableSnapshots_soldOutStatus_purchasableFalse() {
        ProductVariant variant = buildVariant(ProductStatus.SOLD_OUT, true, 5, new BigDecimal("500"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        assertThat(snapshots.get(0).purchasable()).isFalse();
    }

    @Test
    @DisplayName("ON_SALE + inactive → purchasable=false")
    void getOrderableSnapshots_onSaleButInactive_purchasableFalse() {
        ProductVariant variant = buildVariant(ProductStatus.ON_SALE, false, 5, new BigDecimal("500"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        assertThat(snapshots.get(0).purchasable()).isFalse();
    }

    @Test
    @DisplayName("빈 variantIds → 빈 목록 반환(repository 미호출)")
    void getOrderableSnapshots_emptyIds_returnsEmpty() {
        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of());

        assertThat(snapshots).isEmpty();
    }

    @Test
    @DisplayName("반환 타입이 record(OrderableVariantSnapshot)이고 Entity 미포함")
    void getOrderableSnapshots_returnsRecordOnly() {
        ProductVariant variant = buildVariant(ProductStatus.ON_SALE, true, 10, new BigDecimal("1000"));
        when(productVariantRepository.findWithOptionsByIdIn(any())).thenReturn(List.of(variant));

        List<OrderableVariantSnapshot> snapshots = productOrderCatalogImpl.getOrderableSnapshots(List.of(1L));

        // record 타입 확인 — Entity가 아닌 record
        assertThat(snapshots.get(0)).isInstanceOf(OrderableVariantSnapshot.class);
        assertThat(snapshots.get(0)).isNotInstanceOf(ProductVariant.class);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private ProductVariant buildVariant(ProductStatus status, boolean isActive, int stock, BigDecimal price) {
        // Reflection을 피하고 mock으로 Product/ProductVariant 구성
        Product product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(1L);
        when(product.getName()).thenReturn("테스트상품");
        when(product.getStatus()).thenReturn(status);

        ProductOption option = org.mockito.Mockito.mock(ProductOption.class);
        when(option.getName()).thenReturn("색상");

        OptionValue optionValue = org.mockito.Mockito.mock(OptionValue.class);
        when(optionValue.getId()).thenReturn(1L);
        when(optionValue.getValue()).thenReturn("빨강");
        when(optionValue.getOption()).thenReturn(option);

        ProductVariant variant = org.mockito.Mockito.mock(ProductVariant.class);
        when(variant.getId()).thenReturn(1L);
        when(variant.getProduct()).thenReturn(product);
        when(variant.getPrice()).thenReturn(price);
        when(variant.isActive()).thenReturn(isActive);
        when(variant.getStock()).thenReturn(stock);
        when(variant.getOptionValues()).thenReturn(Set.of(optionValue));

        return variant;
    }
}
