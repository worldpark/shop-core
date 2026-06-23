package com.shop.shop.product.search;

/**
 * Elasticsearch 상품 색인 이름·alias·매핑 리소스 상수 단일 출처.
 *
 * <p>버전드 인덱스 {@code products-v1} + alias {@code products}(읽기/쓰기 모두).
 * 매핑 변경 시 새 버전 인덱스(예: {@code products-v2}) 생성 후 alias 스왑(T4 재색인 담당).
 *
 * <p>T4(060) 전량 재색인이 이 상수를 재사용한다.
 */
public final class ProductSearchIndexNames {

    /** 읽기/쓰기에 사용하는 alias 이름. */
    public static final String ALIAS = "products";

    /** 현재 버전 물리 인덱스 이름. 매핑 변경 시 버전 업. */
    public static final String CURRENT_INDEX = "products-v1";

    /** 매핑·settings JSON 클래스패스 리소스 위치. */
    public static final String MAPPING_RESOURCE = "search/product-index.json";

    private ProductSearchIndexNames() {
        // 상수 유틸리티 — 인스턴스화 금지
    }
}
