package com.shop.shop.product.search;

import java.util.List;

/**
 * ES 검색 결과 record — 랭킹 순서 상품 ID 목록 + 총 히트 수.
 *
 * <p>product 모듈 내부 전용. 검색 어댑터({@link ProductSearchPort})의 반환 타입.
 * ES hit._id(=productId)를 랭킹 순서로 보존하므로 호출자는 이 순서를 유지해야 한다.
 *
 * @param ids       ES 랭킹 순서 상품 ID 목록 (hit._id → Long.parseLong)
 * @param totalHits ES totalHits.value (연관도 페이징 count — 드리프트 일시 오차 수용)
 */
public record ProductSearchHits(
        List<Long> ids,
        long totalHits
) {
}
