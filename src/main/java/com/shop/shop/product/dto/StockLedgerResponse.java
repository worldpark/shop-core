package com.shop.shop.product.dto;

import com.shop.shop.inventory.spi.InventoryStockPort.StockLedgerView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 재고 변동 원장 조회 응답 DTO.
 *
 * <p>occurred_at은 KST 오프셋 ISO-8601 문자열로 렌더링한다(ADR-009).
 *
 * @param id             원장 항목 ID
 * @param delta          부호 있는 변동량 (차감=음수, 복원/증분=양수)
 * @param reason         변동 사유 (ORDER_DECREASE / CANCEL_RESTORE / EXPIRY_RESTORE / ADJUSTMENT)
 * @param quantityBefore 변동 전 재고
 * @param quantityAfter  변동 후 재고
 * @param actorId        행위자 ID (시스템=null, 운영자=users.id)
 * @param memo           변동 메모 (ADJUSTMENT 필수, 그 외 null)
 * @param occurredAt     변동 발생 시각 (KST ISO-8601 오프셋 문자열)
 */
public record StockLedgerResponse(
        long id,
        int delta,
        String reason,
        int quantityBefore,
        int quantityAfter,
        Long actorId,
        String memo,
        String occurredAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * {@link StockLedgerView}로부터 응답 DTO 생성 (occurred_at KST 렌더).
     */
    public static StockLedgerResponse from(StockLedgerView view) {
        return new StockLedgerResponse(
                view.id(),
                view.delta(),
                view.reason().name(),
                view.quantityBefore(),
                view.quantityAfter(),
                view.actorId(),
                view.memo(),
                formatKst(view.occurredAt())
        );
    }

    private static String formatKst(Instant instant) {
        return instant.atZone(KST).format(KST_FORMAT);
    }
}
