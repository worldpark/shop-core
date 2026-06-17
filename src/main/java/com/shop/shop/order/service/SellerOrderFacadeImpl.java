package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.SellerOrderFacade;
import com.shop.shop.order.spi.dto.SellerOrderView;
import com.shop.shop.order.spi.dto.SellerOrderView.SellerOrderItemView;
import com.shop.shop.order.spi.dto.SellerOrderView.SellerShipmentView;
import com.shop.shop.order.spi.dto.SellerOrderView.UnshippedOwnedItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link SellerOrderFacade} 구현체 (package-private).
 *
 * <p>order 내부 비공개 {@code service} 패키지에 배치한다.
 * web은 인터페이스({@link SellerOrderFacade})만 참조하며, 이 구현체를 직접 알지 못한다.
 *
 * <p>책임:
 * <ul>
 *   <li>actorEmail → sellerId 해석 ({@link MemberDirectory#findUserIdByEmail(String)})</li>
 *   <li>owner_id = sellerId 인 항목이 포함된 주문 페이지 조회 (최신순)</li>
 *   <li>판매자 소유 항목만 필터링 (타 판매자 항목 제외 — IDOR 방지)</li>
 *   <li>항목별 배송 상태·shipmentId 배치 조회 (IN 1회 — N+1 방지)</li>
 *   <li>SellerShipmentView(판매자 소유 배송 그루핑) + unshippedOwnedItems 조립 (Phase 2)</li>
 *   <li>{@link SellerOrderView} DTO 조립 (Entity 미노출)</li>
 * </ul>
 *
 * <p>소유권: ADMIN 특례 없음 — 판매자 본인 owner_id 기준 조회.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class SellerOrderFacadeImpl implements SellerOrderFacade {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final MemberDirectory memberDirectory;

    /**
     * {@inheritDoc}
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>actorEmail → sellerId ({@link MemberDirectory#findUserIdByEmail})</li>
     *   <li>order_items.owner_id = sellerId 인 주문 페이지 조회 (DISTINCT, createdAt DESC)</li>
     *   <li>페이지가 비면 즉시 반환</li>
     *   <li>페이지의 전체 주문 항목 ID 집합 수집 (판매자 소유 항목만)</li>
     *   <li>shipment_items IN 1회 배치 조회 → Map&lt;orderItemId, [shipmentId, status]&gt; 구성</li>
     *   <li>판매자 소유 배송 그루핑(SellerShipmentView) + 미발송 항목(UnshippedOwnedItem) 조립</li>
     *   <li>주문별 판매자 소유 항목만 필터링 + DTO 조립</li>
     * </ol>
     */
    @Override
    public Page<SellerOrderView> listSellerOrders(String actorEmail, Pageable pageable) {
        long sellerId = memberDirectory.findUserIdByEmail(actorEmail);

        Page<Order> orderPage = orderRepository.findPageBySellerOrderByCreatedAtDescIdDesc(sellerId, pageable);

        if (orderPage.isEmpty()) {
            return orderPage.map(order -> toSellerOrderView(order, sellerId, Map.of(), Map.of(), Set.of()));
        }

        // 페이지 주문의 모든 판매자 소유 항목 ID 집합 수집
        Set<Long> sellerItemIds = orderPage.stream()
                .flatMap(order -> order.getItems().stream())
                .filter(item -> item.getOwnerId() != null && item.getOwnerId() == sellerId)
                .map(OrderItem::getId)
                .collect(Collectors.toSet());

        // shipment_items IN 1회 배치 조회 → Map<orderItemId, [shipmentId, status]>
        // 쿼리 결과: [orderItemId, shipmentId, status]
        Map<Long, Long> shipmentIdByItemId = new HashMap<>();
        Map<Long, String> shipmentStatusByItemId = new HashMap<>();

        if (!sellerItemIds.isEmpty()) {
            List<Object[]> rows = shipmentRepository.findShipmentStatusByOrderItemIdIn(sellerItemIds);
            for (Object[] row : rows) {
                Long orderItemId = (Long) row[0];
                Long shipmentId = (Long) row[1];
                String status = (String) row[2];
                shipmentIdByItemId.put(orderItemId, shipmentId);
                shipmentStatusByItemId.put(orderItemId, status);
            }
        }

        // 판매자 소유 배송 그루핑: shipmentId → [orderItemIds] (seller_id 기준 아닌 owned 항목 기준)
        // 조회된 rows에서 shipmentId → orderItemIds 맵 구성
        Map<Long, List<Long>> orderItemsByShipmentId = new HashMap<>();
        Map<Long, String> statusByShipmentId = new HashMap<>();
        shipmentIdByItemId.forEach((itemId, shipmentId) -> {
            orderItemsByShipmentId.computeIfAbsent(shipmentId, k -> new ArrayList<>()).add(itemId);
            statusByShipmentId.put(shipmentId, shipmentStatusByItemId.get(itemId));
        });

        // 판매자 소유 배정 항목 집합 (미발송 판정용)
        Set<Long> assignedSellerItemIds = shipmentIdByItemId.keySet();

        final Map<Long, Long> finalShipmentIdMap = Map.copyOf(shipmentIdByItemId);
        final Map<Long, String> finalShipmentStatusMap = Map.copyOf(shipmentStatusByItemId);
        final Map<Long, List<Long>> finalOrderItemsByShipmentId = Map.copyOf(orderItemsByShipmentId);
        final Map<Long, String> finalStatusByShipmentId = Map.copyOf(statusByShipmentId);
        final Set<Long> finalAssignedSellerItemIds = Set.copyOf(assignedSellerItemIds);

        return orderPage.map(order -> toSellerOrderView(
                order, sellerId,
                finalShipmentIdMap, finalShipmentStatusMap,
                finalAssignedSellerItemIds,
                finalOrderItemsByShipmentId, finalStatusByShipmentId));
    }

    /**
     * Order Entity → SellerOrderView DTO 변환 (빈 배송/미발송 맵 허용 — 빈 페이지용).
     */
    private SellerOrderView toSellerOrderView(Order order, long sellerId,
                                              Map<Long, Long> shipmentIdByItemId,
                                              Map<Long, String> shipmentStatusByItemId,
                                              Set<Long> assignedSellerItemIds) {
        return toSellerOrderView(order, sellerId, shipmentIdByItemId, shipmentStatusByItemId,
                assignedSellerItemIds, Map.of(), Map.of());
    }

    /**
     * Order Entity → SellerOrderView DTO 변환 (풀 버전).
     *
     * <p>판매자 소유 항목(ownerId == sellerId)만 포함한다(타 판매자 항목 제외).
     *
     * @param order                      주문 Entity
     * @param sellerId                   판매자 ID (소유권 필터 기준)
     * @param shipmentIdByItemId         orderItemId → shipmentId 맵
     * @param shipmentStatusByItemId     orderItemId → shipmentStatus 맵
     * @param assignedSellerItemIds      이미 배정된 판매자 소유 항목 ID 집합
     * @param orderItemsByShipmentId     shipmentId → orderItemIds 그루핑 맵
     * @param statusByShipmentId         shipmentId → status 맵
     * @return SellerOrderView DTO
     */
    private SellerOrderView toSellerOrderView(Order order, long sellerId,
                                              Map<Long, Long> shipmentIdByItemId,
                                              Map<Long, String> shipmentStatusByItemId,
                                              Set<Long> assignedSellerItemIds,
                                              Map<Long, List<Long>> orderItemsByShipmentId,
                                              Map<Long, String> statusByShipmentId) {
        List<OrderItem> ownedItems = order.getItems().stream()
                .filter(item -> item.getOwnerId() != null && item.getOwnerId() == sellerId)
                .toList();

        // 판매자 소유 항목 뷰 (shipmentId 포함)
        List<SellerOrderItemView> sellerItems = ownedItems.stream()
                .map(item -> new SellerOrderItemView(
                        item.getId(),
                        item.getProductName(),
                        item.getOptionLabel(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        shipmentStatusByItemId.get(item.getId()),
                        shipmentIdByItemId.get(item.getId())
                ))
                .toList();

        // 이 주문에서 이 판매자 소유 항목이 속한 배송 그루핑
        Set<Long> ownedItemIds = ownedItems.stream()
                .map(OrderItem::getId)
                .collect(Collectors.toSet());

        List<SellerShipmentView> sellerShipments = orderItemsByShipmentId.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(ownedItemIds::contains))
                .map(e -> new SellerShipmentView(
                        e.getKey(),
                        statusByShipmentId.getOrDefault(e.getKey(), "preparing"),
                        e.getValue().stream().filter(ownedItemIds::contains).toList()
                ))
                .toList();

        // 미발송 owned 항목 (배송 생성 폼용)
        List<UnshippedOwnedItem> unshippedOwnedItems = ownedItems.stream()
                .filter(item -> !assignedSellerItemIds.contains(item.getId()))
                .map(item -> new UnshippedOwnedItem(
                        item.getId(),
                        item.getProductName(),
                        item.getQuantity()
                ))
                .toList();

        return new SellerOrderView(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getFinalAmount(),
                order.getCreatedAt(),
                sellerItems,
                sellerShipments,
                unshippedOwnedItems
        );
    }
}
