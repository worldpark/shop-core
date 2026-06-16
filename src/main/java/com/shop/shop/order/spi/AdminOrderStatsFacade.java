package com.shop.shop.order.spi;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 주문 통계 SPI (published port).
 *
 * <p>order 모듈이 노출하는 통계 전용 facade. 환불율 계산과 상품 판매율 분자 산출에 필요한
 * 최소 카운트/목록만 제공한다.
 *
 * <p>의존 방향: web → order.spi 단방향. order는 product/web을 참조하지 않는다.
 * 판매 variantId는 Long 스칼라로만 노출(product 타입 누설 없음).
 */
public interface AdminOrderStatsFacade {

    /**
     * threshold 이후 생성된 전체 주문 수 (상태 무관).
     * 환불율 분모에 사용.
     *
     * @param threshold 기준 시각 (이 시각 이후 생성된 주문만 집계)
     * @return 해당 기간 전체 주문 수
     */
    long countOrdersSince(Instant threshold);

    /**
     * threshold 이후 생성된 환불(refunded) 주문 수.
     * 환불율 분자에 사용.
     *
     * @param threshold 기준 시각
     * @return 해당 기간 refunded 주문 수
     */
    long countRefundedSince(Instant threshold);

    /**
     * threshold 이후 생성된 완료 판매 주문의 DISTINCT variantId 목록.
     * 상품 판매율 분자 산출 시 product.spi에 전달하기 위해 사용.
     *
     * <p>완료 상태: paid / preparing / shipping / delivered.
     * variantId가 NULL인 주문 항목(삭제된 variant)은 자동 제외된다.
     *
     * @param threshold 기준 시각
     * @return 조건에 맞는 DISTINCT variantId 목록 (NULL 제외)
     */
    List<Long> distinctSoldVariantIdsSince(Instant threshold);
}
