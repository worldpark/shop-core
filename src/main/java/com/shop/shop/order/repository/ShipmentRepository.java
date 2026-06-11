package com.shop.shop.order.repository;

import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.domain.ShipmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Shipment JPA Repository.
 *
 * <p>ShipmentItem 조회도 이 리포지토리에 통합한다 (별도 ShipmentItemRepository 불필요).
 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /**
     * shipmentId → orderId 스칼라 projection (정합1 — 락 전 orderId 획득용).
     *
     * <p>ship 흐름에서 orderId를 알기 위해 엔티티 적재 없이 스칼라만 반환한다.
     * {@code findById(...).getOrderId()} 대신 반드시 이 메서드를 사용해야 한다.
     * 엔티티를 락 전에 적재하면 JPA 1차 캐시가 stale 상태를 보관해 동시 ship 시
     * 이벤트 중복 발행이 발생한다(정합1 — stale read 차단).
     *
     * @param id 배송 ID
     * @return orderId (배송 미존재 시 empty)
     */
    @Query("select s.orderId from Shipment s where s.id = :id")
    Optional<Long> findOrderIdById(@Param("id") long id);

    /**
     * 주문별 배송 목록 조회.
     *
     * <p>REST GET /api/v1/admin/orders/{orderId}/shipments 및 admin facade에서 사용.
     *
     * @param orderId 주문 ID
     * @return 해당 주문의 배송 목록 (없으면 빈 목록)
     */
    List<Shipment> findByOrderId(long orderId);

    /**
     * 이미 배정된 주문 항목 ID 목록 조회.
     *
     * <p>미발송 항목 판정에 사용: order.getItems() − findAssignedOrderItemIds(orderId).
     * 한 주문의 모든 shipment에 속한 order_item_id를 반환한다.
     *
     * @param orderId 주문 ID
     * @return 해당 주문의 shipment_items.order_item_id 목록
     */
    @Query("select si.orderItemId from ShipmentItem si where si.shipment.orderId = :orderId")
    List<Long> findAssignedOrderItemIds(@Param("orderId") long orderId);

    /**
     * 여러 주문의 배송 목록 배치 조회 (admin facade listFulfillableOrders N+1 제거).
     *
     * <p>페이지 단위 주문 id 집합에 대해 한 번에 조회 후 메모리에서 groupingBy 분배.
     *
     * @param orderIds 주문 ID 집합
     * @return 해당 주문들의 배송 목록
     */
    @Query("select s from Shipment s where s.orderId in :orderIds")
    List<Shipment> findByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);

    /**
     * 여러 주문의 이미 배정된 주문 항목 ID 목록 배치 조회 (admin facade listFulfillableOrders N+1 제거).
     *
     * @param orderIds 주문 ID 집합
     * @return 해당 주문들의 shipment_items.order_item_id 목록
     */
    @Query("select si.orderItemId from ShipmentItem si where si.shipment.orderId in :orderIds")
    List<Long> findAssignedOrderItemIdsByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);
}
