package com.shop.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.shop.shop.inventory.spi.StockChangeReason;
import java.time.Instant;

/**
 * 재고 변동 원장 Entity.
 *
 * <p>테이블: inventory_stock_ledger (V8__inventory_stock_ledger.sql).
 * 성공한 모든 재고 변동을 사유·전후 수량·행위자·발생시각과 함께 기록하는 감사 원장이다.
 *
 * <p>BaseEntity 미상속 — 이 테이블은 트리거 updated_at 컬럼이 없으며 {@code occurred_at}만 시각 컬럼.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #of} 사용. INSERT 전용(UPDATE 없음).
 *
 * <p>수량 컬럼({@code delta}/{@code quantity_before}/{@code quantity_after})은 전부 {@code int} —
 * product_variants.stock(int)와 동형. smallint 회피(smallint↔int 매핑이 entityManagerFactory를 깸).
 */
@Entity
@Table(name = "inventory_stock_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false)
    private long variantId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockChangeReason reason;

    @Column(name = "quantity_before", nullable = false)
    private int quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(columnDefinition = "text")
    private String memo;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * 재고 변동 원장 항목 정적 팩토리.
     *
     * @param variantId      변동 대상 variant ID
     * @param delta          부호 있는 변동량 (차감=음수, 복원/증분=양수)
     * @param reason         변동 사유
     * @param quantityBefore 변동 전 재고
     * @param quantityAfter  변동 후 재고
     * @param actorId        행위자 ID (시스템=null, 운영자=users.id)
     * @param memo           변동 메모 (ADJUSTMENT 필수, 그 외 null)
     * @param occurredAt     변동 발생 시각
     * @return 생성된 StockLedgerEntry
     */
    public static StockLedgerEntry of(long variantId, int delta, StockChangeReason reason,
                                       int quantityBefore, int quantityAfter,
                                       Long actorId, String memo, Instant occurredAt) {
        StockLedgerEntry entry = new StockLedgerEntry();
        entry.variantId = variantId;
        entry.delta = delta;
        entry.reason = reason;
        entry.quantityBefore = quantityBefore;
        entry.quantityAfter = quantityAfter;
        entry.actorId = actorId;
        entry.memo = memo;
        entry.occurredAt = occurredAt;
        return entry;
    }
}
