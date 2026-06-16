package com.shop.shop.order.spi;

import com.shop.shop.order.spi.dto.VariantSalesAggregate;

import java.util.Collection;
import java.util.List;

/**
 * 판매자 판매 집계 SPI (published port).
 *
 * <p>order 모듈이 소유권을 모른 채 주어진 variantId 집합의 완료 판매를 집계하는 포트.
 * 소유권 검사는 호출자(web 계층) 책임이며, order는 자기 테이블(orders/order_items)만 조회한다.
 *
 * <p>판매 인정 상태: paid / preparing / shipping / delivered.
 * cancelled / refunded / pending 은 제외.
 *
 * <p>의존 방향: web → order.spi 단방향. order는 product/web을 참조하지 않는다.
 */
public interface SellerSalesStatsPort {

    /**
     * 주어진 variantId 집합의 판매 집계를 반환한다.
     *
     * <p>판매 인정 상태(paid/preparing/shipping/delivered)의 order_items만 집계한다.
     * variantIds가 비어 있으면 DB 조회 없이 빈 리스트를 즉시 반환한다.
     * variantId가 NULL인 order_items(삭제된 variant)는 자동 제외된다.
     * 판매가 없는 variantId는 결과에 포함되지 않는다(salesQty=0 항목 미포함 — 합산 시 0으로 처리).
     *
     * @param variantIds 집계 대상 variant ID 컬렉션 (비면 빈 리스트 반환)
     * @return variantId별 판매 집계 리스트
     */
    List<VariantSalesAggregate> aggregateByVariantIds(Collection<Long> variantIds);
}
