package com.shop.shop.order.spi.dto;

import java.math.BigDecimal;

/**
 * 단일 variant의 판매 집계 결과.
 *
 * <p>판매 인정 상태(paid/preparing/shipping/delivered) 주문의 order_items를 variantId 기준으로 집계한 결과.
 * cancelled/refunded/pending 주문은 제외된다.
 * variantId가 NULL인 order_items(삭제된 variant)는 집계에서 자동 제외된다.
 *
 * @param variantId variant ID
 * @param salesQty  판매 수량 합계
 * @param revenue   매출(lineAmount) 합계
 */
public record VariantSalesAggregate(
        long variantId,
        long salesQty,
        BigDecimal revenue
) {
}
