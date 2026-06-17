package com.shop.shop.web.product.dto;

import java.util.Map;

/**
 * 판매자 판매 현황 SSE 스냅샷 payload DTO.
 *
 * <p>SSE event {@code name=stats}의 {@code data}로 JSON 직렬화되어 브라우저에 전송된다.
 * 클라이언트는 {@link #salesByProduct()} 맵의 productId를 현재 렌더된 표 행과 매칭해
 * 판매수량·매출 셀만 패치(페이지네이션 디커플 — plan §1).
 *
 * <p>직렬화 가능(record, Jackson 기본 설정으로 JSON 변환 가능).
 * Map 키는 Long productId, 값은 {@link SalesCell}.
 *
 * @param salesByProduct productId → {@link SalesCell} 맵
 */
public record SellerSalesSnapshot(
        Map<Long, SalesCell> salesByProduct
) {
}
