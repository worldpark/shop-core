package com.shop.shop.order.service;

import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.dto.AdminOrderFulfillmentView.UnshippedItem;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link AdminOrderFulfillmentFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link AdminOrderFulfillmentFacade})만 참조하며,
 * 이 구현체를 직접 알지 못한다 ({@link com.shop.shop.member.service.AdminMemberFacadeImpl} 선례).
 *
 * <p>책임:
 * <ul>
 *   <li>paid/preparing 주문 페이지 조회 + 미발송 항목 + 배송 현황 → {@link AdminOrderFulfillmentView} 변환</li>
 *   <li>{@link OrderFulfillmentService#createShipment} 위임 + {@link ShipmentResponse} 전파</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class AdminOrderFulfillmentFacadeImpl implements AdminOrderFulfillmentFacade {

    private static final List<String> FULFILLABLE_STATUSES = List.of("paid", "preparing");

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final OrderFulfillmentService orderFulfillmentService;

    /**
     * {@inheritDoc}
     *
     * <p>paid/preparing 주문 페이지 조회 → 각 주문의 미발송 항목·배송 현황 집계 →
     * {@link AdminOrderFulfillmentView} DTO 변환.
     *
     * <p>미발송 항목 = order.getItems() − 이미 shipment_items에 배정된 order_item_id.
     *
     * <p>페이지의 모든 주문 id에 대해 배송·배정 항목을 배치 조회하여
     * 주문마다 2회씩 호출하던 N+1 패턴을 제거한다.
     */
    @Override
    public Page<AdminOrderFulfillmentView> listFulfillableOrders(Pageable pageable) {
        Page<Order> orders = orderRepository.findByStatusInOrderByCreatedAtDescIdDesc(
                FULFILLABLE_STATUSES, pageable);

        if (orders.isEmpty()) {
            return orders.map(order -> toAdminOrderFulfillmentView(order, Set.of(), List.of()));
        }

        // 페이지 주문 id 집합에 대해 배치 조회
        Set<Long> orderIds = orders.stream()
                .map(Order::getId)
                .collect(Collectors.toSet());

        List<Long> allAssignedIds = shipmentRepository.findAssignedOrderItemIdsByOrderIdIn(orderIds);
        Set<Long> allAssignedSet = Set.copyOf(allAssignedIds);

        List<Shipment> allShipments = shipmentRepository.findByOrderIdIn(orderIds);
        Map<Long, List<Shipment>> shipmentsByOrderId = allShipments.stream()
                .collect(Collectors.groupingBy(Shipment::getOrderId));

        return orders.map(order -> toAdminOrderFulfillmentView(
                order, allAssignedSet, shipmentsByOrderId.getOrDefault(order.getId(), List.of())));
    }

    /**
     * {@inheritDoc}
     *
     * <p>BusinessException(400/409)은 변환 없이 그대로 전파한다 (web이 catch → flashError).
     */
    @Override
    @Transactional
    public ShipmentResponse createShipment(long orderId, List<Long> orderItemIds) {
        return orderFulfillmentService.createShipment(orderId, orderItemIds);
    }

    // ============================================================
    // 변환 헬퍼
    // ============================================================

    /**
     * 배치 조회된 assignedSet·shipments를 받아 뷰 DTO로 변환한다.
     * 리포지토리를 직접 호출하지 않으므로 주문마다 추가 쿼리가 발생하지 않는다.
     */
    private AdminOrderFulfillmentView toAdminOrderFulfillmentView(
            Order order, Set<Long> allAssignedSet, List<Shipment> orderShipments) {
        long orderId = order.getId();

        // 미발송 항목
        List<UnshippedItem> unshippedItems = order.getItems().stream()
                .filter(item -> !allAssignedSet.contains(item.getId()))
                .map(item -> new UnshippedItem(
                        item.getId(),
                        item.getProductName(),
                        item.getQuantity()
                ))
                .toList();

        // 배송 현황
        Map<Long, OrderItem> orderItemMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));

        List<ShipmentResponse> shipmentResponses = orderShipments.stream()
                .map(shipment -> toShipmentResponseWithItems(shipment, orderItemMap))
                .toList();

        return new AdminOrderFulfillmentView(
                orderId,
                order.getOrderNumber(),
                order.getStatus(),
                unshippedItems,
                shipmentResponses
        );
    }

    private ShipmentResponse toShipmentResponseWithItems(Shipment shipment,
                                                          Map<Long, OrderItem> orderItemMap) {
        List<ShipmentItemResponse> itemResponses = shipment.getItems().stream()
                .map(si -> {
                    OrderItem orderItem = orderItemMap.get(si.getOrderItemId());
                    // 정상 흐름 미발생(order_item은 항상 존재) — 방어용
                    return new ShipmentItemResponse(
                            si.getOrderItemId(),
                            orderItem != null ? orderItem.getProductName() : "",
                            orderItem != null ? orderItem.getQuantity() : 0
                    );
                })
                .toList();

        return new ShipmentResponse(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getStatus(),
                itemResponses
        );
    }
}
