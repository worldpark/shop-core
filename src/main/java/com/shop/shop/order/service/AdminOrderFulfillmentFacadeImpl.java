package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.dto.AdminOrderFulfillmentView;
import com.shop.shop.order.dto.AdminOrderFulfillmentView.AdminShipmentView;
import com.shop.shop.order.dto.AdminOrderFulfillmentView.UnshippedItem;
import com.shop.shop.order.dto.DeliverResponse;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.AdminOrderFulfillmentFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
 *   <li>paid/preparing/shipping(이행 중) 주문 페이지 조회 + 미발송 항목 + 배송 현황 → {@link AdminOrderFulfillmentView} 변환</li>
 *   <li>delivered/cancelled/refunded(종결) 주문은 제외</li>
 *   <li>{@link OrderFulfillmentService#createShipment} 위임 + {@link ShipmentResponse} 전파</li>
 *   <li>admin 감독: 배송별 seller 구분 표기 ({@link AdminShipmentView#sellerLabel()}) — ROLE_ADMIN 한정</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class AdminOrderFulfillmentFacadeImpl implements AdminOrderFulfillmentFacade {

    private static final List<String> FULFILLABLE_STATUSES = List.of("paid", "preparing", "shipping");

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final OrderFulfillmentService orderFulfillmentService;
    private final MemberDirectory memberDirectory;

    /**
     * {@inheritDoc}
     *
     * <p>paid/preparing/shipping(이행 중) 주문 페이지 조회 → 각 주문의 미발송 항목·배송 현황 집계 →
     * {@link AdminOrderFulfillmentView} DTO 변환.
     *
     * <p>미발송 항목 = order.getItems() − 이미 shipment_items에 배정된 order_item_id.
     *
     * <p>페이지의 모든 주문 id에 대해 배송·배정 항목을 배치 조회하여
     * 주문마다 2회씩 호출하던 N+1 패턴을 제거한다.
     *
     * <p>seller 이름 해석: 페이지 내 distinct non-null sellerId에 대해 1회씩 {@link MemberDirectory#findContactByUserId} 호출.
     * 판매자 계정이 삭제·비활성화된 경우 {@link IllegalStateException}이 발생하며,
     * 이를 try/catch로 잡아 "판매자(#N)" fallback으로 강등한다 — 페이지 조회는 절대 비차단(MAJOR-1).
     */
    @Override
    public Page<AdminOrderFulfillmentView> listFulfillableOrders(Pageable pageable) {
        Page<Order> orders = orderRepository.findByStatusInOrderByCreatedAtDescIdDesc(
                FULFILLABLE_STATUSES, pageable);

        if (orders.isEmpty()) {
            return orders.map(order -> toAdminOrderFulfillmentView(order, Set.of(), List.of(), Map.of()));
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

        // ★ MAJOR-1 — N+1 방지: 페이지 내 distinct non-null sellerId를 한 번에 수집 → 이름 맵 구성
        // 판매자 계정이 삭제·비활성화돼도 seller_id 스냅샷은 잔존하므로, 이름 해석 실패 시 fallback 강등.
        Map<Long, String> sellerNames = buildSellerNamesMap(allShipments);

        return orders.map(order -> toAdminOrderFulfillmentView(
                order, allAssignedSet, shipmentsByOrderId.getOrDefault(order.getId(), List.of()), sellerNames));
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

    /**
     * {@inheritDoc}
     *
     * <p>BusinessException(404/409)은 변환 없이 그대로 전파한다 (web이 catch → flashError).
     */
    @Override
    @Transactional
    public ShipmentResponse ship(long shipmentId, String carrier, String trackingNumber) {
        return orderFulfillmentService.ship(shipmentId, carrier, trackingNumber);
    }

    /**
     * {@inheritDoc}
     *
     * <p>BusinessException(404/409)은 변환 없이 그대로 전파한다 (web이 catch → flashError).
     */
    @Override
    @Transactional
    public DeliverResponse deliver(long shipmentId) {
        return orderFulfillmentService.deliver(shipmentId);
    }

    // ============================================================
    // 변환 헬퍼
    // ============================================================

    /**
     * 배치 조회된 assignedSet·shipments를 받아 뷰 DTO로 변환한다.
     * 리포지토리를 직접 호출하지 않으므로 주문마다 추가 쿼리가 발생하지 않는다.
     */
    private AdminOrderFulfillmentView toAdminOrderFulfillmentView(
            Order order, Set<Long> allAssignedSet, List<Shipment> orderShipments,
            Map<Long, String> sellerNames) {
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

        List<AdminShipmentView> shipmentViews = orderShipments.stream()
                .map(shipment -> toAdminShipmentView(shipment, orderItemMap, sellerNames))
                .toList();

        return new AdminOrderFulfillmentView(
                orderId,
                order.getOrderNumber(),
                order.getStatus(),
                unshippedItems,
                shipmentViews
        );
    }

    /**
     * Shipment → {@link AdminShipmentView} 변환.
     *
     * <p>sellerLabel 결정 규칙:
     * <ul>
     *   <li>sellerId null → "관리자 직접 처리" (admin 생성 배송)</li>
     *   <li>sellerId non-null → sellerNames 맵 조회 (맵에 없는 경우는 buildSellerNamesMap에서 fallback 처리됨)</li>
     * </ul>
     */
    private AdminShipmentView toAdminShipmentView(Shipment shipment, Map<Long, OrderItem> orderItemMap,
                                                   Map<Long, String> sellerNames) {
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

        Long sellerId = shipment.getSellerId();
        String sellerLabel = resolveSellerLabel(sellerId, sellerNames);

        return new AdminShipmentView(
                shipment.getId(),
                shipment.getStatus(),
                sellerId,
                sellerLabel,
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt(),
                itemResponses
        );
    }

    /**
     * sellerId → 표기 레이블 결정.
     *
     * <p>null이면 "관리자 직접 처리", non-null이면 sellerNames 맵 조회.
     * 맵에 키가 없는 경우는 buildSellerNamesMap에서 이미 fallback으로 처리되었으므로 발생하지 않는다.
     * 방어적으로 맵에 없으면 fallback 레이블을 반환한다.
     */
    private String resolveSellerLabel(Long sellerId, Map<Long, String> sellerNames) {
        if (sellerId == null) {
            return "관리자 직접 처리";
        }
        return sellerNames.getOrDefault(sellerId, "판매자(#" + sellerId + ")");
    }

    /**
     * 페이지 내 모든 배송의 distinct non-null sellerId를 수집해 이름 맵을 구성한다.
     *
     * <p>★ MAJOR-1 fallback: {@link MemberDirectory#findContactByUserId}가 {@link IllegalStateException}을
     * 던지는 경우(판매자 계정 삭제·비활성화로 인한 스냅샷 불일치) try/catch로 잡아
     * "판매자(#N)" fallback으로 강등한다. 페이지 조회는 절대 비차단.
     *
     * <p>N+1 방지: 페이지당 distinct seller 수만큼만 MemberDirectory를 호출한다 (보통 소수).
     * MemberDirectory에 배치 메서드는 신설하지 않는다(과설계 회피, plan §5).
     *
     * @param allShipments 페이지 내 전체 배송 목록
     * @return sellerId → 판매자명 맵 (해석 실패 시 fallback 레이블 포함)
     */
    private Map<Long, String> buildSellerNamesMap(List<Shipment> allShipments) {
        Set<Long> distinctSellerIds = allShipments.stream()
                .map(Shipment::getSellerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, String> sellerNames = new HashMap<>();
        for (Long sellerId : distinctSellerIds) {
            try {
                MemberDirectory.MemberContact contact = memberDirectory.findContactByUserId(sellerId);
                sellerNames.put(sellerId, contact.name());
            } catch (IllegalStateException e) {
                // ★ MAJOR-1: 판매자 계정이 삭제·비활성화돼도 seller_id 스냅샷은 잔존.
                // 이름 해석 실패 시 fallback 레이블로 강등 — 페이지 조회 비차단.
                log.warn("판매자 이름 해석 실패 — fallback 적용: sellerId={}", sellerId, e);
                sellerNames.put(sellerId, "판매자(#" + sellerId + ")");
            }
        }
        return sellerNames;
    }
}
