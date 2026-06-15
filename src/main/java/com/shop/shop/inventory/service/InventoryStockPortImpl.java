package com.shop.shop.inventory.service;

import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.common.exception.VariantNotFoundException;
import com.shop.shop.inventory.domain.StockLedgerEntry;
import com.shop.shop.inventory.spi.StockChangeReason;
import com.shop.shop.inventory.domain.VariantStock;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.inventory.repository.StockLedgerRepository;
import com.shop.shop.inventory.spi.InventoryStockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * {@link InventoryStockPort} 구현체 (package-private).
 *
 * <p>inventory 내부 비공개 {@code service} 패키지에 배치한다.
 * order/product는 인터페이스({@link InventoryStockPort})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>{@code findByIdForUpdate}로 비관적 락 획득 → 검증 → 재고 변동 → 원장 atomically 적재</li>
 *   <li>{@code increase()} variant 미존재 skip 경로는 원장 미기록 보장</li>
 *   <li>product Entity/Repository/Service 직접 참조 금지</li>
 * </ul>
 *
 * <p>모든 메서드는 호출자(order/product)의 @Transactional 경계 안에서 실행되거나
 * 자체 @Transactional(adjustStock, getLedger)로 실행된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class InventoryStockPortImpl implements InventoryStockPort {

    private final InventoryStockRepository inventoryStockRepository;
    private final StockLedgerRepository stockLedgerRepository;

    /**
     * {@inheritDoc}
     *
     * <p>1. SELECT ... FOR UPDATE (비관적 락 획득)
     * 2. 미존재 → InsufficientStockException(409)
     * 3. isActive==false → InsufficientStockException(409)
     * 4. stock &lt; quantity → InsufficientStockException(409)
     * 5. before 캡처 → vs.decrease(quantity) → after 캡처
     * 6. 원장 INSERT (delta=-quantity, reason, actorId=null, memo=null)
     */
    @Override
    public void decrease(long variantId, int quantity, StockChangeContext context) {
        VariantStock variantStock = inventoryStockRepository.findByIdForUpdate(variantId)
                .orElseThrow(() -> new InsufficientStockException("재고 정보를 찾을 수 없습니다."));

        if (!variantStock.isActive()) {
            throw new InsufficientStockException("비활성 상태의 상품입니다.");
        }

        if (variantStock.getStock() < quantity) {
            throw new InsufficientStockException("재고가 부족합니다.");
        }

        int before = variantStock.getStock();
        variantStock.decrease(quantity);
        int after = variantStock.getStock();

        stockLedgerRepository.save(StockLedgerEntry.of(
                variantId, -quantity, context.reason(),
                before, after, context.actorId(), context.memo(), Instant.now()
        ));
    }

    /**
     * {@inheritDoc}
     *
     * <p>1. SELECT ... FOR UPDATE (비관적 락 획득)
     * 2. 미존재(변형 삭제됨) → 복원 skip + 로깅(원장 미기록, InsufficientStockException 미발생)
     * 3. isActive 미검사(비활성 변형도 재고 복원)
     * 4. before 캡처 → vs.increase(quantity) → after 캡처
     * 5. 원장 INSERT (delta=+quantity, reason, actorId=null, memo=null)
     */
    @Override
    public void increase(long variantId, int quantity, StockChangeContext context) {
        inventoryStockRepository.findByIdForUpdate(variantId)
                .ifPresentOrElse(
                        variantStock -> {
                            int before = variantStock.getStock();
                            variantStock.increase(quantity);
                            int after = variantStock.getStock();
                            stockLedgerRepository.save(StockLedgerEntry.of(
                                    variantId, quantity, context.reason(),
                                    before, after, context.actorId(), context.memo(), Instant.now()
                            ));
                        },
                        () -> log.warn("재고 복원 skip: variantId={} 에 해당하는 VariantStock row 없음 (변형 삭제됨) — 원장 미기록", variantId)
                );
    }

    /**
     * {@inheritDoc}
     *
     * <p>1. SELECT ... FOR UPDATE (비관적 락 획득)
     * 2. 미존재 → VariantNotFoundException(404)
     * 3. newStock = stock + delta → newStock &lt; 0 → InsufficientStockException(409)
     * 4. isActive 미검사(비활성 variant도 실사 보정 허용)
     * 5. before 캡처 → stock 갱신 → after 캡처
     * 6. 원장 INSERT (reason=ADJUSTMENT, actorId, memo)
     */
    @Override
    public StockLedgerView adjustStock(long variantId, int delta, long actorId, String memo) {
        VariantStock variantStock = inventoryStockRepository.findByIdForUpdate(variantId)
                .orElseThrow(VariantNotFoundException::new);

        int newStock = variantStock.getStock() + delta;
        if (newStock < 0) {
            throw new InsufficientStockException("조정 결과 재고가 0 미만이 됩니다.");
        }

        int before = variantStock.getStock();
        if (delta > 0) {
            variantStock.increase(delta);
        } else {
            variantStock.decrease(-delta);
        }
        int after = variantStock.getStock();

        StockLedgerEntry entry = stockLedgerRepository.save(StockLedgerEntry.of(
                variantId, delta, StockChangeReason.ADJUSTMENT,
                before, after, actorId, memo, Instant.now()
        ));
        return new StockLedgerView(
                entry.getId(),
                entry.getVariantId(),
                entry.getDelta(),
                entry.getReason(),
                entry.getQuantityBefore(),
                entry.getQuantityAfter(),
                entry.getActorId(),
                entry.getMemo(),
                entry.getOccurredAt()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public Page<StockLedgerView> getLedger(long variantId, Pageable pageable) {
        return stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(variantId, pageable)
                .map(entry -> new StockLedgerView(
                        entry.getId(),
                        entry.getVariantId(),
                        entry.getDelta(),
                        entry.getReason(),
                        entry.getQuantityBefore(),
                        entry.getQuantityAfter(),
                        entry.getActorId(),
                        entry.getMemo(),
                        entry.getOccurredAt()
                ));
    }
}
