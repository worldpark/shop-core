package com.shop.shop.inventory.spi;

/**
 * 재고 차감 published port (inventory 소유).
 *
 * <p>비관적 락 기반 재고 검증·차감 포트.
 * order 모듈이 주문 생성 트랜잭션 안에서 사용한다.
 *
 * <p>구현 전략 (Revision 1 확정):
 * <ul>
 *   <li>{@code VariantStock} Entity로 {@code product_variants}(id·stock·is_active 컬럼만 매핑)를 잠근다.</li>
 *   <li>{@code @Lock(LockModeType.PESSIMISTIC_WRITE)} → PostgreSQL {@code SELECT ... FOR UPDATE}</li>
 *   <li>같은 variant에 대한 동시 주문은 row 단위로 직렬화된다.</li>
 *   <li>다중 variant 주문: 호출자(order)가 variantId 오름차순으로 순차 호출해야 한다.</li>
 *   <li>product Entity/Repository/Service를 직접 참조하지 않는다.</li>
 * </ul>
 *
 * <p>의존 방향: order → inventory.spi 단방향. inventory는 order를 참조하지 않는다.
 */
public interface InventoryStockPort {

    /**
     * 비관적 락 재고 검증·차감.
     *
     * <p>실행 순서:
     * <ol>
     *   <li>variantId로 VariantStock row를 {@code SELECT ... FOR UPDATE} 잠금</li>
     *   <li>미존재 → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>isActive==false → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>stock &lt; quantity → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>stock -= quantity (JPA dirty checking으로 UPDATE)</li>
     * </ol>
     *
     * <p>호출자(order)는 다중 variant 주문 시 variantId 오름차순으로 호출해야 한다 (데드락 완화).
     * 이 메서드는 order의 @Transactional 경계 안에서 실행되어야 한다.
     *
     * @param variantId variant ID
     * @param quantity  차감할 수량 (≥ 1)
     * @throws com.shop.shop.common.exception.InsufficientStockException
     *         미존재·비활성·재고 부족 시 409 (상태 충돌)
     */
    void decrease(long variantId, int quantity);

    /**
     * 비관적 락 재고 복원 (취소/환불 시).
     *
     * <p>실행 순서:
     * <ol>
     *   <li>variantId로 VariantStock row를 {@code SELECT ... FOR UPDATE} 잠금</li>
     *   <li>row 미존재(변형 삭제됨) → 복원 대상 아님 — 복원 skip + 로깅(예외 미발생)</li>
     *   <li>isActive 미검사(비활성 변형도 재고 복원)</li>
     *   <li>stock += quantity (JPA dirty checking으로 UPDATE)</li>
     * </ol>
     *
     * <p>호출자(OrderCancellationImpl)는:
     * <ul>
     *   <li>variantId가 null인 항목은 이 메서드를 호출하지 않고 skip+log(best-effort)</li>
     *   <li>다중 variant는 variantId 오름차순으로 순차 호출(데드락 완화, decrease와 동일 규약)</li>
     * </ul>
     *
     * <p>이 메서드는 order의 @Transactional 경계 안에서 실행되어야 한다.
     *
     * @param variantId variant ID
     * @param quantity  복원할 수량 (≥ 1)
     */
    void increase(long variantId, int quantity);
}
