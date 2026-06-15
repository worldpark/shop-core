package com.shop.shop.inventory.service;

import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.inventory.spi.StockChangeReason;
import com.shop.shop.inventory.domain.VariantStock;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.inventory.repository.StockLedgerRepository;
import com.shop.shop.inventory.spi.InventoryStockPort.StockChangeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InventoryStockPortImpl} 단위 테스트 (Mockito).
 *
 * <p>검증:
 * <ul>
 *   <li>decrease: 재고 충분 → 원장 INSERT + decrease 호출</li>
 *   <li>decrease: 재고 부족 → InsufficientStockException(409)</li>
 *   <li>decrease: 비활성 → InsufficientStockException(409)</li>
 *   <li>decrease: 미존재 → InsufficientStockException(409)</li>
 *   <li>adjustStock: 음수 재고 결과 → InsufficientStockException(409)</li>
 *   <li>adjustStock: before/after 캡처 인자 정확성</li>
 *   <li>increase: 미존재 → 원장 미기록</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryStockPortImplTest {

    @Mock
    private InventoryStockRepository inventoryStockRepository;

    @Mock
    private StockLedgerRepository stockLedgerRepository;

    private InventoryStockPortImpl inventoryStockPortImpl;

    @BeforeEach
    void setUp() {
        inventoryStockPortImpl = new InventoryStockPortImpl(inventoryStockRepository, stockLedgerRepository);
    }

    // ============================================================
    // decrease
    // ============================================================

    @Test
    @DisplayName("재고 충분: decrease 호출 + 원장 1건 INSERT")
    void decrease_sufficientStock_callsDecreaseAndSavesLedger() {
        VariantStock variantStock = buildVariantStock(true, 10);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        inventoryStockPortImpl.decrease(1L, 3, StockChangeContext.system(StockChangeReason.ORDER_DECREASE));

        verify(variantStock).decrease(3);
        verify(stockLedgerRepository).save(any());
    }

    @Test
    @DisplayName("재고 부족(stock=2, quantity=5) → InsufficientStockException(409), 원장 미기록")
    void decrease_insufficientStock_throwsAndNoLedger() {
        VariantStock variantStock = buildVariantStock(true, 2);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(1L, 5,
                StockChangeContext.system(StockChangeReason.ORDER_DECREASE)))
                .isInstanceOf(InsufficientStockException.class);

        verify(variantStock, never()).decrease(5);
        verify(stockLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("비활성(isActive=false) → InsufficientStockException(409), 원장 미기록")
    void decrease_inactiveVariant_throwsAndNoLedger() {
        VariantStock variantStock = buildVariantStock(false, 10);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(1L, 1,
                StockChangeContext.system(StockChangeReason.ORDER_DECREASE)))
                .isInstanceOf(InsufficientStockException.class);

        verify(variantStock, never()).decrease(1);
        verify(stockLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("미존재 variantId → InsufficientStockException(409)")
    void decrease_nonExistentVariant_throwsInsufficientStockException() {
        when(inventoryStockRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryStockPortImpl.decrease(999L, 1,
                StockChangeContext.system(StockChangeReason.ORDER_DECREASE)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("정확히 stock 수량만큼 차감 성공 (경계값: quantity=stock)")
    void decrease_exactlyStockQuantity_succeeds() {
        VariantStock variantStock = buildVariantStock(true, 5);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        inventoryStockPortImpl.decrease(1L, 5, StockChangeContext.system(StockChangeReason.ORDER_DECREASE));

        verify(variantStock).decrease(5);
    }

    // ============================================================
    // increase — 미존재 skip → 원장 미기록
    // ============================================================

    @Test
    @DisplayName("increase variant 미존재 → skip + 원장 미기록")
    void increase_nonExistentVariant_noLedgerEntry() {
        when(inventoryStockRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        inventoryStockPortImpl.increase(999L, 1, StockChangeContext.system(StockChangeReason.CANCEL_RESTORE));

        verify(stockLedgerRepository, never()).save(any());
    }

    // ============================================================
    // adjustStock
    // ============================================================

    @Test
    @DisplayName("adjustStock: 음수 재고 결과(stock=5, delta=-10) → InsufficientStockException(409)")
    void adjustStock_negativeResult_throwsInsufficientStockException() {
        VariantStock variantStock = buildVariantStock(true, 5);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));

        assertThatThrownBy(() -> inventoryStockPortImpl.adjustStock(1L, -10, 99L, "테스트"))
                .isInstanceOf(InsufficientStockException.class);

        verify(stockLedgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("adjustStock: variant 미존재 → VariantNotFoundException(404)")
    void adjustStock_nonExistentVariant_throwsVariantNotFoundException() {
        when(inventoryStockRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryStockPortImpl.adjustStock(999L, 5, 1L, "테스트"))
                .isInstanceOf(VariantNotFoundException.class);
    }

    @Test
    @DisplayName("adjustStock: before/after 캡처 정확 (stock=10, delta=+3 → before=10, after=13)")
    void adjustStock_capturesToBeforeAndAfter() {
        VariantStock variantStock = mock(VariantStock.class);
        when(variantStock.isActive()).thenReturn(true);
        // getStock()이 before 캡처(10) → increase 후 after 캡처(13) 순으로 반환
        when(variantStock.getStock()).thenReturn(10, 10, 13);
        when(inventoryStockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(variantStock));
        when(stockLedgerRepository.save(any())).thenAnswer(inv -> {
            var savedEntry = (com.shop.shop.inventory.domain.StockLedgerEntry) inv.getArgument(0);
            try {
                var idField = com.shop.shop.inventory.domain.StockLedgerEntry.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(savedEntry, 1L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return savedEntry;
        });

        inventoryStockPortImpl.adjustStock(1L, 3, 99L, "입고");

        var ledgerCaptor = ArgumentCaptor.forClass(com.shop.shop.inventory.domain.StockLedgerEntry.class);
        verify(stockLedgerRepository).save(ledgerCaptor.capture());
        var entry = ledgerCaptor.getValue();

        assertThat(entry.getDelta()).isEqualTo(3);
        assertThat(entry.getQuantityBefore()).isEqualTo(10);
        assertThat(entry.getQuantityAfter()).isEqualTo(13);
        assertThat(entry.getActorId()).isEqualTo(99L);
        assertThat(entry.getMemo()).isEqualTo("입고");
        assertThat(entry.getReason()).isEqualTo(StockChangeReason.ADJUSTMENT);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private VariantStock buildVariantStock(boolean isActive, int stock) {
        VariantStock vs = mock(VariantStock.class);
        when(vs.isActive()).thenReturn(isActive);
        when(vs.getStock()).thenReturn(stock);
        return vs;
    }
}
