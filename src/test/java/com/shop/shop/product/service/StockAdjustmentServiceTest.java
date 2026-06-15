package com.shop.shop.product.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.inventory.spi.StockChangeReason;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.inventory.spi.InventoryStockPort.StockLedgerView;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StockAdjustmentService} 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>소유권 위반 → ProductAccessDeniedException(404)</li>
 *   <li>상품 미존재 → ProductNotFoundException(404)</li>
 *   <li>variant↔product 불일치 → VariantNotFoundException(404)</li>
 *   <li>memo 공란 → BusinessException(400)</li>
 *   <li>delta=0 → BusinessException(400)</li>
 *   <li>정상 위임: inventoryStockPort.adjustStock 인자 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockAdjustmentServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private InventoryStockPort inventoryStockPort;

    private StockAdjustmentService stockAdjustmentService;

    private static final long ACTOR_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long VARIANT_ID = 100L;

    @BeforeEach
    void setUp() {
        stockAdjustmentService = new StockAdjustmentService(
                productService, productVariantRepository, inventoryStockPort);
    }

    // ============================================================
    // 소유권 404
    // ============================================================

    @Test
    @DisplayName("소유권 위반 → ProductAccessDeniedException(404)")
    void adjustStock_ownershipViolation_throwsProductAccessDeniedException() {
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID))
                .thenThrow(new ProductAccessDeniedException(PRODUCT_ID));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, "테스트"))
                .isInstanceOf(ProductAccessDeniedException.class);

        verify(inventoryStockPort, never()).adjustStock(anyLong(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("상품 미존재 → ProductNotFoundException(404)")
    void adjustStock_productNotFound_throwsProductNotFoundException() {
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID))
                .thenThrow(new ProductNotFoundException(PRODUCT_ID));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, "테스트"))
                .isInstanceOf(ProductNotFoundException.class);

        verify(inventoryStockPort, never()).adjustStock(anyLong(), anyInt(), anyLong(), anyString());
    }

    // ============================================================
    // variant↔product 소속 404
    // ============================================================

    @Test
    @DisplayName("variant↔product 불일치 → VariantNotFoundException(404)")
    void adjustStock_variantProductMismatch_throwsVariantNotFoundException() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        // variantId가 다른 productId에 속함
        Product otherProduct = mockProduct(999L);
        ProductVariant variant = mockVariant(VARIANT_ID, otherProduct);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, "테스트"))
                .isInstanceOf(VariantNotFoundException.class);

        verify(inventoryStockPort, never()).adjustStock(anyLong(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("variant 미존재 → VariantNotFoundException(404)")
    void adjustStock_variantNotFound_throwsVariantNotFoundException() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, "테스트"))
                .isInstanceOf(VariantNotFoundException.class);
    }

    // ============================================================
    // memo 공란 400
    // ============================================================

    @Test
    @DisplayName("memo 공란('') → BusinessException(400)")
    void adjustStock_blankMemo_throwsBusinessException400() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductVariant variant = mockVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("memo null → BusinessException(400)")
    void adjustStock_nullMemo_throwsBusinessException400() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductVariant variant = mockVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 5, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ============================================================
    // delta=0 400
    // ============================================================

    @Test
    @DisplayName("delta=0 → BusinessException(400)")
    void adjustStock_deltaZero_throwsBusinessException400() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductVariant variant = mockVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, 0, "조정 사유"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(inventoryStockPort, never()).adjustStock(anyLong(), anyInt(), anyLong(), anyString());
    }

    // ============================================================
    // 정상 위임
    // ============================================================

    @Test
    @DisplayName("정상 위임: inventoryStockPort.adjustStock 인자 검증")
    void adjustStock_valid_delegatesToInventoryPort() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, false, PRODUCT_ID)).thenReturn(product);

        ProductVariant variant = mockVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        StockLedgerView mockView = new StockLedgerView(1L, VARIANT_ID, -3,
                StockChangeReason.ADJUSTMENT, 10, 7, ACTOR_ID, "손상 폐기", Instant.now());
        when(inventoryStockPort.adjustStock(eq(VARIANT_ID), eq(-3), eq(ACTOR_ID), eq("손상 폐기")))
                .thenReturn(mockView);

        StockLedgerView result = stockAdjustmentService.adjustStock(
                ACTOR_ID, false, PRODUCT_ID, VARIANT_ID, -3, "손상 폐기");

        verify(inventoryStockPort).adjustStock(eq(VARIANT_ID), eq(-3), eq(ACTOR_ID), eq("손상 폐기"));
        assertThat(result.delta()).isEqualTo(-3);
        assertThat(result.quantityBefore()).isEqualTo(10);
        assertThat(result.quantityAfter()).isEqualTo(7);
    }

    @Test
    @DisplayName("ADMIN actorIsAdmin=true → 소유권 바이패스 후 정상 위임")
    void adjustStock_adminBypass_delegatesToInventoryPort() {
        Product product = mockProduct(PRODUCT_ID);
        when(productService.getOwnedProduct(ACTOR_ID, true, PRODUCT_ID)).thenReturn(product);

        ProductVariant variant = mockVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        StockLedgerView mockView = new StockLedgerView(2L, VARIANT_ID, 10,
                StockChangeReason.ADJUSTMENT, 5, 15, ACTOR_ID, "재고 보충", Instant.now());
        when(inventoryStockPort.adjustStock(eq(VARIANT_ID), eq(10), eq(ACTOR_ID), eq("재고 보충")))
                .thenReturn(mockView);

        StockLedgerView result = stockAdjustmentService.adjustStock(
                ACTOR_ID, true, PRODUCT_ID, VARIANT_ID, 10, "재고 보충");

        verify(inventoryStockPort).adjustStock(eq(VARIANT_ID), eq(10), eq(ACTOR_ID), eq("재고 보충"));
        assertThat(result.delta()).isEqualTo(10);
        assertThat(result.quantityBefore()).isEqualTo(5);
        assertThat(result.quantityAfter()).isEqualTo(15);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Product mockProduct(long productId) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        return product;
    }

    private ProductVariant mockVariant(long variantId, Product product) {
        ProductVariant variant = mock(ProductVariant.class);
        when(variant.getId()).thenReturn(variantId);
        when(variant.getProduct()).thenReturn(product);
        return variant;
    }
}
