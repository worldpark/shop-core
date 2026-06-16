package com.shop.shop.product.spi;

import java.util.Collection;

/**
 * 관리자 상품 통계 SPI (published port).
 *
 * <p>product 모듈이 노출하는 통계 전용 facade. 상품 판매율 분모(게시 상품 수)와
 * 분자(판매된 게시 상품 distinct 수) 계산에 필요한 카운트만 제공한다.
 *
 * <p>의존 방향: web → product.spi 단방향. product는 order/web을 참조하지 않는다.
 * soldVariantIds는 Long 스칼라 컬렉션(order 타입 누설 없음).
 */
public interface AdminProductStatsFacade {

    /**
     * 게시 상품 수 조회 (DRAFT / HIDDEN 제외).
     * 상품 판매율 분모에 사용.
     *
     * <p>게시 상품 = ON_SALE + SOLD_OUT.
     *
     * @return 게시 상품 수
     */
    long countPublishedProducts();

    /**
     * 주어진 variantId 집합 중 게시 상품에 속한 distinct 상품 수.
     * 상품 판매율 분자에 사용.
     *
     * <p>soldVariantIds가 비어 있으면 DB 조회 없이 0을 즉시 반환한다(빈 컬렉션 가드).
     * 미게시 상품(DRAFT/HIDDEN)에 속한 variant는 제외된다.
     * 삭제된 variant(NULL)는 호출 전 order.spi 결과에서 이미 제외된다.
     *
     * @param soldVariantIds 최근 30일 완료 판매 variantId 컬렉션 (비면 0 반환)
     * @return 판매된 게시 상품 distinct 수
     */
    long countPublishedProductsWithSales(Collection<Long> soldVariantIds);
}
