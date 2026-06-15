package com.shop.shop.order.adapter;

import com.shop.shop.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 실구매 검증용 order_item 경량 projection.
     *
     * @param userId    주문 소유자 userId (소유권 검증)
     * @param status    주문 상태 (배송 완료 여부 판별)
     * @param variantId variant ID (nullable — FK ON DELETE SET NULL)
     */
    record OrderItemProjection(long userId, String status, Long variantId) {}
}
