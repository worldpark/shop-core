package com.shop.shop.inventory.service;

import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.inventory.domain.VariantStock;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InventoryStockPortImpl} 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>재고 충분: decrease 호출 성공</li>
 *   <li>재고 부족: InsufficientStockException(409)</li>
 *   <li>비활성: InsufficientStockException(409)</li>
 *   <li>미존재: InsufficientStockException(409)</li>
 *   <li>product Entity/Repository/Service 미참조</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryStockPortImplTest {

    @Mock
    private InventoryStockRepository inventoryStockRepository;

    private InventoryStockPortImpl inventoryStockPortImpl;

    @BeforeEach
    void setUp() {
        inventoryStockPortImpl = new InventoryStockPortImpl(inventoryStockRepository);
    }

    @Test
    @DisplayName("재고 충분: decrease 호출, InsufficientStockException 미발생")
    void decrease_sufficientStock_callsVariantStockDecrease() {
        VariantStock variantStock = buildVariantStock(true, 10);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        inventoryStockPortImpl.decrease(1L, 3);

        verify(variantStock).decrease(3);
    }

    @Test
    @DisplayName("재고 부족(stock=2, quantity=5) → InsufficientStockException(409)")
    void decrease_insufficientStock_throwsInsufficientStockException() {
        VariantStock variantStock = buildVariantStock(true, 2);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(1L, 5))
                .isInstanceOf(InsufficientStockException.class);

        verify(variantStock, never()).decrease(5);
    }

    @Test
    @DisplayName("비활성(isActive=false) → InsufficientStockException(409)")
    void decrease_inactiveVariant_throwsInsufficientStockException() {
        VariantStock variantStock = buildVariantStock(false, 10);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(1L, 1))
                .isInstanceOf(InsufficientStockException.class);

        verify(variantStock, never()).decrease(1);
    }

    @Test
    @DisplayName("미존재 variantId → InsufficientStockException(409)")
    void decrease_nonExistentVariant_throwsInsufficientStockException() {
        when(inventoryStockRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(999L, 1))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("정확히 stock 수량만큼 차감 성공 (경계값: quantity=stock)")
    void decrease_exactlyStockQuantity_succeeds() {
        VariantStock variantStock = buildVariantStock(true, 5);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        inventoryStockPortImpl.decrease(1L, 5);

        verify(variantStock).decrease(5);
    }

    private VariantStock buildVariantStock(boolean isActive, int stock) {
        VariantStock vs = mock(VariantStock.class);
        when(vs.isActive()).thenReturn(isActive);
        when(vs.getStock()).thenReturn(stock);
        return vs;
    }
}
