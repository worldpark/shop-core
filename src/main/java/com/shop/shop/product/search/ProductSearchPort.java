package com.shop.shop.product.search;

import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.service.PublicProductSort;

import java.util.List;

/**
 * 상품 검색 읽기 포트 (product 모듈 내부 전용).
 *
 * <p>구현체({@link EsProductSearchAdapter})는 Elasticsearch 클라이언트를 사용한다.
 * ES가 없거나 비활성화된 환경에서는 이 포트의 빈이 존재하지 않으며,
 * 호출자({@code PublicProductService})는 {@code ObjectProvider}를 통해 선택적으로 주입한다.
 *
 * <p>구현체는 모든 예외를 내부에서 흡수하고 "비가용" 신호(empty Optional / 쿨다운 중 즉시 반환)를
 * 통해 호출자가 폴백 경로로 전환하도록 신호한다. 도메인 예외를 이 포트 밖으로 누수시키지 않는다.
 */
public interface ProductSearchPort {

    /**
     * 키워드 기반 상품 검색.
     *
     * <p>ES bool 쿼리: must(multi_match) + filter(status terms + optional categoryId term) + sort + from/size.
     * ES 장애(연결/타임아웃/5xx) 또는 쿨다운 중일 때는 empty를 반환해 호출자가 PG 폴백으로 전환한다.
     *
     * @param keyword    검색어 (null이면 이 포트를 호출하지 않는다 — 호출자 책임)
     * @param categoryId 카테고리 필터 (null이면 전체)
     * @param statuses   노출 허용 status 목록 (ES terms 필터)
     * @param sort       정렬 기준
     * @param page       0-based 페이지 번호
     * @param size       페이지 크기 (1~100)
     * @return 검색 결과 (빈 Optional = ES 비가용 신호 → 호출자가 PG 폴백 수행)
     */
    java.util.Optional<ProductSearchHits> search(
            String keyword,
            Long categoryId,
            List<ProductStatus> statuses,
            PublicProductSort sort,
            int page,
            int size
    );
}
