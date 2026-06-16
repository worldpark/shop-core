package com.shop.shop.order.repository;

import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.spi.dto.VariantSalesAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * order_items 판매 집계 전용 리포지토리.
 *
 * <p>판매자 현황 페이지의 variantId별 판매 집계를 담당한다.
 * 판매 인정 상태: paid / preparing / shipping / delivered.
 * cancelled / refunded / pending 제외.
 * variantId NULL 행(삭제된 variant)은 IN 절에 걸리지 않아 자동 제외.
 */
public interface OrderItemSalesRepository extends JpaRepository<OrderItem, Long> {

    /**
     * variantId 집합 기준 판매 집계 쿼리.
     *
     * <p>COUNT → SUM(quantity), SUM(lineAmount) — COALESCE로 NULL 방어.
     * o.status는 lowercase String 스칼라 — IN 절에 문자열 리터럴 집합 파라미터 그대로 사용.
     * variantId NULL 행은 IN :variantIds 조건에 안 걸려 자동 제외.
     *
     * @param variantIds      집계 대상 variant ID 컬렉션
     * @param countedStatuses 판매 인정 상태 집합 (paid/preparing/shipping/delivered)
     * @return variantId별 판매 집계 리스트 (판매 없는 variantId는 미포함)
     */
    @Query("""
            SELECT new com.shop.shop.order.spi.dto.VariantSalesAggregate(
                oi.variantId,
                COALESCE(SUM(oi.quantity), 0),
                COALESCE(SUM(oi.lineAmount), 0)
            )
            FROM OrderItem oi JOIN oi.order o
            WHERE oi.variantId IN :variantIds
              AND o.status IN :countedStatuses
            GROUP BY oi.variantId
            """)
    List<VariantSalesAggregate> aggregateSalesByVariantIds(
            @Param("variantIds") Collection<Long> variantIds,
            @Param("countedStatuses") Collection<String> countedStatuses
    );
}
