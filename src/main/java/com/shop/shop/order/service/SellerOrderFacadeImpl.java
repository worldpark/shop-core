package com.shop.shop.order.service;

import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.spi.SellerOrderFacade;
import com.shop.shop.order.spi.dto.SellerOrderView;
import com.shop.shop.order.spi.dto.SellerOrderView.SellerOrderItemView;
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
 *   <li>항목별 배송 상태 배치 조회 (IN 1회 — N+1 방지)</li>
 *   <li>{@link SellerOrderView} DTO 조립 (Entity 미노출)</li>
 * </ul>
 *
 * <p>소유권: ADMIN 특례 없음 — 판매자 본인 owner_id 기준 조회.
 * Phase 1: 읽기 전용. 배송 쓰기는 Phase 2(049).
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
     *   <li>페이지의 전체 주문 항목 ID 집합 수집</li>
     *   <li>shipment_items IN 1회 배치 조회 → Map&lt;orderItemId, shipmentStatus&gt; 구성</li>
     *   <li>주문별로 판매자 소유 항목만 필터링 + DTO 조립</li>
     * </ol>
     */
    @Override
    public Page<SellerOrderView> listSellerOrders(String actorEmail, Pageable pageable) {
        long sellerId = memberDirectory.findUserIdByEmail(actorEmail);

        Page<Order> orderPage = orderRepository.findPageBySellerOrderByCreatedAtDescIdDesc(sellerId, pageable);

        if (orderPage.isEmpty()) {
            return orderPage.map(order -> toSellerOrderView(order, sellerId, Map.of()));
        }

        // 페이지 주문의 모든 항목 ID 집합 수집 (판매자 소유 항목만 대상)
        Set<Long> sellerItemIds = orderPage.stream()
                .flatMap(order -> order.getItems().stream())
                .filter(item -> item.getOwnerId() != null && item.getOwnerId() == sellerId)
                .map(OrderItem::getId)
                .collect(Collectors.toSet());

        // shipment_items IN 1회 배치 조회 → Map<orderItemId, shipmentStatus>
        Map<Long, String> shipmentStatusByItemId = Map.of();
        if (!sellerItemIds.isEmpty()) {
            List<Object[]> rows = shipmentRepository.findShipmentStatusByOrderItemIdIn(sellerItemIds);
            shipmentStatusByItemId = rows.stream()
                    .collect(Collectors.toMap(
                            row -> (Long) row[0],
                            row -> (String) row[1]
                    ));
        }

        final Map<Long, String> finalShipmentStatusMap = shipmentStatusByItemId;
        return orderPage.map(order -> toSellerOrderView(order, sellerId, finalShipmentStatusMap));
    }

    /**
     * Order Entity → SellerOrderView DTO 변환.
     *
     * <p>판매자 소유 항목(ownerId == sellerId)만 포함한다(타 판매자 항목 제외).
     *
     * @param order                주문 Entity
     * @param sellerId             판매자 ID (소유권 필터 기준)
     * @param shipmentStatusByItemId orderItemId → shipmentStatus 맵
     * @return SellerOrderView DTO
     */
    private SellerOrderView toSellerOrderView(Order order, long sellerId,
                                              Map<Long, String> shipmentStatusByItemId) {
        List<SellerOrderItemView> sellerItems = order.getItems().stream()
                .filter(item -> item.getOwnerId() != null && item.getOwnerId() == sellerId)
                .map(item -> new SellerOrderItemView(
                        item.getId(),
                        item.getProductName(),
                        item.getOptionLabel(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        shipmentStatusByItemId.get(item.getId()) // 미존재 = null (배송 미생성)
                ))
                .toList();

        return new SellerOrderView(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getFinalAmount(),
                order.getCreatedAt(),
                sellerItems
        );
    }
}
