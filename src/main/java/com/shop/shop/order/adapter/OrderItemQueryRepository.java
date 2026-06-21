package com.shop.shop.order.adapter;

import com.shop.shop.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 실구매 검증용 경량 order_item 조회 리포지토리 (order 모듈 내부 전용).
 *
 * <p>adapter({@link OrderPurchaseVerificationAdapter})에서만 사용한다.
 * Entity를 외부(product 모듈)에 노출하지 않는다.
 *
 * <p>projection 쿼리: OrderItemProjection record(userId, status, variantId)만 반환.
 * Order Entity 전체를 로드하지 않아 성능 효율적.
 */
public interface OrderItemQueryRepository extends JpaRepository<OrderItem, Long> {

    /**
     * order_item + order(userId·status·variantId) 경량 projection 조회.
     *
     * <p>소유권 검증(userId)·상태 조회(status)·variant 해석(variantId)에 필요한 스칼라만 반환한다.
     *
     * @param orderItemId 주문 항목 ID
     * @return projection (없으면 empty)
     */
    @Query("""
            select new com.shop.shop.order.adapter.OrderItemQueryRepository$OrderItemProjection(
                o.userId, o.status, oi.variantId
            )
            from OrderItem oi
            join oi.order o
            where oi.id = :orderItemId
            """)
    Optional<OrderItemProjection> findOrderItemProjection(@Param("orderItemId") long orderItemId);

    /**
     * 사용자 소유 + 배송완료(delivered) + 주어진 variant 집합에 속한 order_item id 목록 조회.
     *
     * <p>상품 상세 "리뷰 작성" 진입점 노출 판단용. order.status는 lowercase varchar이므로
     * 'delivered' 문자열 리터럴로 비교한다. variantId가 null(ON DELETE SET NULL)인 항목은
     * IN 조건에서 자연히 제외된다.
     *
     * @param userId     주문 소유자 userId
     * @param variantIds 대상 variant ID 집합
     * @return 조건을 만족하는 order_item id 목록
     */
    @Query("""
            select oi.id
            from OrderItem oi
            join oi.order o
            where o.userId = :userId
              and o.status = 'delivered'
              and oi.variantId in :variantIds
            """)
    List<Long> findDeliveredOrderItemIds(@Param("userId") long userId,
                                         @Param("variantIds") Collection<Long> variantIds);

    /**
     * 실구매 검증용 order_item 경량 projection.
     *
     * @param userId    주문 소유자 userId (소유권 검증)
     * @param status    주문 상태 (배송 완료 여부 판별)
     * @param variantId variant ID (nullable — FK ON DELETE SET NULL)
     */
    record OrderItemProjection(long userId, String status, Long variantId) {}
}
