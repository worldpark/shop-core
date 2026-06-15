package com.shop.shop.inventory.spi;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

/**
 * 재고 published port (inventory 소유).
 *
 * <p>비관적 락 기반 재고 검증·차감·복원·조정 포트.
 * order/product 모듈이 inventory.spi를 통해서만 재고를 조작한다.
 *
 * <p>구현 전략:
 * <ul>
 *   <li>{@code VariantStock} Entity로 {@code product_variants}(id·stock·is_active 컬럼만 매핑)를 잠근다.</li>
 *   <li>{@code @Lock(LockModeType.PESSIMISTIC_WRITE)} → PostgreSQL {@code SELECT ... FOR UPDATE}</li>
 *   <li>같은 variant에 대한 동시 조작은 row 단위로 직렬화된다.</li>
 *   <li>다중 variant: 호출자가 variantId 오름차순으로 순차 호출해야 한다(데드락 완화).</li>
 *   <li>product Entity/Repository/Service를 직접 참조하지 않는다.</li>
 * </ul>
 *
 * <p>의존 방향: order → inventory.spi, product → inventory.spi 단방향.
 * inventory는 order/product를 참조하지 않는다.
 *
 * <p>포트 시그니처에는 primitive·inventory 소유 enum({@link StockChangeReason})·{@link Pageable}만 사용.
 * web 타입(HttpServletRequest 등) 수수 금지(architecture-rule).
 */
public interface InventoryStockPort {

    // ============================================================
    // 동반 타입
    // ============================================================

    /**
     * 재고 변동 맥락 (reason·actorId·memo).
     *
     * <p>호출자가 변동의 의미적 맥락을 inventory 포트로 전달해 원장 atomically 적재를 가능하게 한다.
     * actorId=null, memo=null 은 시스템 변동({@link #system(StockChangeReason)}).
     * actorId·memo 는 운영자 조정({@link #operator(StockChangeReason, long, String)}).
     */
    record StockChangeContext(StockChangeReason reason, Long actorId, String memo) {

        /**
         * 시스템 변동 컨텍스트 팩토리 (주문·취소·만료 경로).
         * actorId=null, memo=null.
         */
        public static StockChangeContext system(StockChangeReason reason) {
            return new StockChangeContext(reason, null, null);
        }

        /**
         * 운영자 조정 컨텍스트 팩토리 (ADJUSTMENT 경로).
         * actorId = 운영자 users.id, memo = 조정 사유.
         */
        public static StockChangeContext operator(StockChangeReason reason, long actorId, String memo) {
            return new StockChangeContext(reason, actorId, memo);
        }
    }

    /**
     * 재고 변동 원장 조회 뷰 DTO (inventory.spi 소유, Entity 미노출).
     *
     * <p>호출자(product 계층)는 이 record를 그대로 통과시켜 응답 DTO 변환은 web facade에서 수행한다.
     */
    record StockLedgerView(
            long id,
            long variantId,
            int delta,
            StockChangeReason reason,
            int quantityBefore,
            int quantityAfter,
            Long actorId,
            String memo,
            Instant occurredAt
    ) {
    }

    // ============================================================
    // 포트 메서드
    // ============================================================

    /**
     * 비관적 락 재고 검증·차감 + 원장 적재.
     *
     * <p>실행 순서:
     * <ol>
     *   <li>variantId로 VariantStock row를 {@code SELECT ... FOR UPDATE} 잠금</li>
     *   <li>미존재 → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>isActive==false → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>stock &lt; quantity → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>before 캡처 → stock -= quantity → after 캡처</li>
     *   <li>원장 INSERT (delta=-quantity, reason=ctx.reason(), actorId=ctx.actorId(), memo=ctx.memo())</li>
     * </ol>
     *
     * <p>호출자(order)는 다중 variant 주문 시 variantId 오름차순으로 호출해야 한다 (데드락 완화).
     * 이 메서드는 order의 @Transactional 경계 안에서 실행되어야 한다.
     *
     * @param variantId variant ID
     * @param quantity  차감할 수량 (≥ 1)
     * @param context   변동 맥락 (reason=ORDER_DECREASE, actorId=null, memo=null)
     * @throws com.shop.shop.common.exception.InsufficientStockException
     *         미존재·비활성·재고 부족 시 409
     */
    void decrease(long variantId, int quantity, StockChangeContext context);

    /**
     * 비관적 락 재고 복원 (취소/환불 시) + 원장 적재.
     *
     * <p>실행 순서:
     * <ol>
     *   <li>variantId로 VariantStock row를 {@code SELECT ... FOR UPDATE} 잠금</li>
     *   <li>row 미존재(변형 삭제됨) → 복원 skip + 로깅(원장 미기록, 예외 미발생)</li>
     *   <li>isActive 미검사(비활성 변형도 재고 복원)</li>
     *   <li>before 캡처 → stock += quantity → after 캡처</li>
     *   <li>원장 INSERT (delta=+quantity, reason=ctx.reason(), actorId=null, memo=null)</li>
     * </ol>
     *
     * <p>호출자(OrderCancellationImpl)는:
     * <ul>
     *   <li>variantId가 null인 항목은 이 메서드를 호출하지 않고 skip+log(best-effort)</li>
     *   <li>다중 variant는 variantId 오름차순으로 순차 호출(데드락 완화)</li>
     *   <li>취소 경로: context=system(CANCEL_RESTORE), 만료 경로: context=system(EXPIRY_RESTORE)</li>
     * </ul>
     *
     * <p>이 메서드는 order의 @Transactional 경계 안에서 실행되어야 한다.
     *
     * @param variantId variant ID
     * @param quantity  복원할 수량 (≥ 1)
     * @param context   변동 맥락 (reason=CANCEL_RESTORE 또는 EXPIRY_RESTORE, actorId=null, memo=null)
     */
    void increase(long variantId, int quantity, StockChangeContext context);

    /**
     * 운영자 재고 조정 + 원장 적재 (reason=ADJUSTMENT 고정).
     *
     * <p>실행 순서:
     * <ol>
     *   <li>variantId로 VariantStock row를 {@code SELECT ... FOR UPDATE} 잠금</li>
     *   <li>미존재 → {@link com.shop.shop.common.exception.VariantNotFoundException}(404)
     *       — 조정은 명시적 단건 대상이라 미존재는 부정확 입력(404). 주문 차감 409와 구분.</li>
     *   <li>newStock = stock + delta 계산 → newStock &lt; 0 → {@link com.shop.shop.common.exception.InsufficientStockException}(409)</li>
     *   <li>isActive 미검사(비활성 variant도 실사 보정 허용)</li>
     *   <li>before 캡처 → stock 갱신 → after 캡처</li>
     *   <li>원장 INSERT (reason=ADJUSTMENT, actorId=운영자, memo=조정사유)</li>
     * </ol>
     *
     * @param variantId variant ID
     * @param delta     부호 있는 조정량 (양수=증가, 음수=감소, 0 허용 안함 — 호출자 책임)
     * @param actorId   운영자 users.id
     * @param memo      조정 사유 (필수)
     * @return 적재된 원장 뷰 (before/after/occurredAt)
     * @throws com.shop.shop.common.exception.VariantNotFoundException variant 미존재(404)
     * @throws com.shop.shop.common.exception.InsufficientStockException 조정 결과 음수 재고(409)
     */
    StockLedgerView adjustStock(long variantId, int delta, long actorId, String memo);

    /**
     * variant별 재고 변동 원장 조회 (최신순).
     *
     * @param variantId variant ID
     * @param pageable  페이지 정보
     * @return 원장 뷰 Page (occurred_at DESC, id DESC 정렬)
     */
    Page<StockLedgerView> getLedger(long variantId, Pageable pageable);
}
