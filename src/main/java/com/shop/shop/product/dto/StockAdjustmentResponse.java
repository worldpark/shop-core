package com.shop.shop.product.dto;

import com.shop.shop.inventory.spi.InventoryStockPort.StockLedgerView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 재고 조정 응답 DTO.
 *
 * <p>occurred_at은 KST 오프셋 ISO-8601 문자열로 렌더링한다(ADR-009).
 * 저장 시각은 Instant(UTC)이며 응답에서만 KST 변환.
 *
 * @param variantId      조정 대상 variant ID
 * @param delta          부호 있는 조정량
 * @param quantityBefore 조정 전 재고
 * @param quantityAfter  조정 후 재고
 * @param occurredAt     조정 발생 시각 (KST ISO-8601 오프셋 문자열)
 */
public record StockAdjustmentResponse(
        long variantId,
        int delta,
        int quantityBefore,
        int quantityAfter,
        String occurredAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * {@link StockLedgerView}로부터 응답 DTO 생성 (occurred_at KST 렌더).
     */
    public static StockAdjustmentResponse of(StockLedgerView view) {
        return new StockAdjustmentResponse(
                view.variantId(),
                view.delta(),
                view.quantityBefore(),
                view.quantityAfter(),
                formatKst(view.occurredAt())
        );
    }

    private static String formatKst(Instant instant) {
        return instant.atZone(KST).format(KST_FORMAT);
    }
}
