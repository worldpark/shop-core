package com.shop.shop.web.product.dto;

import java.math.BigDecimal;

/**
 * 상품별 판매 집계 셀 (SSE payload 내부 단위).
 *
 * <p>{@link SellerSalesSnapshot#salesByProduct()} 맵의 값 타입.
 * variant별 집계를 productId 기준으로 병합한 결과를 담는다.
 *
 * @param salesQty 판매 수량 합계 (paid/preparing/shipping/delivered 주문 기준)
 * @param revenue  매출(lineAmount) 합계 (동일 기준)
 */
public record SalesCell(
        long salesQty,
        BigDecimal revenue
) {
    /** 판매 없는 상품의 기본값 — salesQty=0, revenue=ZERO. */
    public static final SalesCell ZERO = new SalesCell(0L, BigDecimal.ZERO);
}
