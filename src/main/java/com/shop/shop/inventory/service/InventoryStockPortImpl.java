package com.shop.shop.inventory.service;

import com.shop.shop.common.exception.InsufficientStockException;
import com.shop.shop.inventory.domain.VariantStock;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.inventory.spi.InventoryStockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InventoryStockPort} 구현체 (package-private).
 *
 * <p>inventory 내부 비공개 {@code service} 패키지에 배치한다.
 * order는 인터페이스({@link InventoryStockPort})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>{@code findByIdForUpdate}로 비관적 락 획득 → isActive·stock 순차 검증 → 차감</li>
 *   <li>미존재·비활성·재고 부족 → {@link InsufficientStockException}(409)</li>
 *   <li>product Entity/Repository/Service 직접 참조 금지</li>
 * </ul>
 *
 * <p>이 메서드는 호출자(order)의 @Transactional 경계 안에서 실행된다.
 * 호출자는 다중 variant 주문 시 variantId 오름차순으로 이 메서드를 순차 호출해야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class InventoryStockPortImpl implements InventoryStockPort {

    private final InventoryStockRepository inventoryStockRepository;

    /**
     * {@inheritDoc}
     *
     * <p>1. SELECT ... FOR UPDATE (비관적 락 획득)
     * 2. 미존재 → InsufficientStockException(409)
     * 3. isActive==false → InsufficientStockException(409)
     * 4. stock &lt; quantity → InsufficientStockException(409)
     * 5. vs.decrease(quantity) — dirty checking으로 stock UPDATE
     */
    @Override
    public void decrease(long variantId, int quantity) {
        VariantStock variantStock = inventoryStockRepository.findByIdForUpdate(variantId)
                .orElseThrow(() -> new InsufficientStockException("재고 정보를 찾을 수 없습니다."));

        if (!variantStock.isActive()) {
            throw new InsufficientStockException("비활성 상태의 상품입니다.");
        }

        if (variantStock.getStock() < quantity) {
            throw new InsufficientStockException("재고가 부족합니다.");
        }

        variantStock.decrease(quantity);
    }

    /**
     * {@inheritDoc}
     *
     * <p>1. SELECT ... FOR UPDATE (비관적 락 획득)
     * 2. 미존재(변형 삭제됨) → 복원 skip + 로깅(InsufficientStockException 미발생)
     * 3. isActive 미검사(비활성 변형도 재고 복원)
     * 4. vs.increase(quantity) — dirty checking으로 stock UPDATE
     */
    @Override
    public void increase(long variantId, int quantity) {
        inventoryStockRepository.findByIdForUpdate(variantId)
                .ifPresentOrElse(
                        variantStock -> variantStock.increase(quantity),
                        () -> log.warn("재고 복원 skip: variantId={} 에 해당하는 VariantStock row 없음 (변형 삭제됨)", variantId)
                );
    }
}
