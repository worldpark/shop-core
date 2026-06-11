package com.shop.shop.order.service;

import com.shop.shop.common.exception.InvalidShipmentItemException;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.domain.ShipmentItem;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 주문 이행(Fulfillment) 서비스 — public 클래스(모순①).
 *
 * <p>{@code AdminOrderFulfillmentRestController}({@code order/controller})와
 * {@code AdminOrderFulfillmentFacadeImpl}({@code order/service})가 다른 패키지에서 직접 호출하므로
 * public이어야 한다.
 *
 * <p>의존: {@link OrderRepository}·{@link ShipmentRepository}만 — 결제/재고/이벤트 의존 0.
 * 배송 생성은 외부계(payment/inventory/Kafka)와 무관하다 (새 cross-module 의존 0).
 *
 * <p>관리자 단일 주체 — 소유권 검사 불요 (ROLE_ADMIN 전역 권한 보장).
 * 미존재 주문은 평범한 404 반환 (관리자 경로 — 존재 은닉 불요).
 *
 * <p><b>본 Task(019)는 preparing 생성 + paid→preparing rollup까지만 구현한다.</b>
 * shipping/delivered 전이·ShippingStartedEvent 발행은 020/021 소관.
 * 이벤트 발행 0 — event_publication 무변화.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderFulfillmentService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;

    /**
     * 배송 생성 — 주문 row 비관락 + 상태/항목 검증 + Shipment/ShipmentItem 생성 + rollup.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>주문 row {@code PESSIMISTIC_WRITE} 잠금 — 동시 배송 생성·018 취소와 직렬화</li>
     *   <li>상태 검증: {@code paid}/{@code preparing}이 아니면 {@link OrderFulfillmentConflictException}(409, 부작용 전 throw)</li>
     *   <li>대상 항목 결정:
     *     <ul>
     *       <li>{@code orderItemIds} 지정 시: 미존재/타 주문 소속 → 400, 이미 배정 → 409</li>
     *       <li>{@code orderItemIds} 생략(null/빈) 시: 미발송 항목 전부</li>
     *       <li>대상 0건 → 409</li>
     *     </ul>
     *   </li>
     *   <li>Shipment.preparing + ShipmentItem 생성 → save</li>
     *   <li>rollup: {@code paid}면 markPreparing() (첫 배송). 이미 {@code preparing}이면 no-op (status 불변)</li>
     *   <li>원자 커밋 → {@link ShipmentResponse} 반환</li>
     * </ol>
     *
     * <p>동시성: 주문 row 락(같은 주문 동시 요청 직렬화) + {@code shipment_items.order_item_id} UNIQUE(최후 방어).
     * 락 우회 UNIQUE 위반 → {@link DataIntegrityViolationException} → 409로 매핑.
     *
     * @param orderId      대상 주문 ID
     * @param orderItemIds 포함할 주문 항목 ID 목록 (null/빈 목록 = 미발송 항목 전부)
     * @return 생성된 배송 응답 DTO
     * @throws OrderNotFoundException             미존재 주문 (404)
     * @throws OrderFulfillmentConflictException  상태 충돌 (409, 부작용 전 판정)
     * @throws InvalidShipmentItemException       입력 오류 (400, orderItemId 미존재/타 주문 소속)
     */
    public ShipmentResponse createShipment(long orderId, List<Long> orderItemIds) {
        // 1. 주문 row PESSIMISTIC_WRITE 잠금 (018 취소·동시 배송 생성과 직렬화)
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        String currentStatus = order.getStatus();

        // 2. 상태 검증 — 부작용 전 throw (모순2)
        if (!"paid".equals(currentStatus) && !"preparing".equals(currentStatus)) {
            log.warn("배송 생성 불가 — 주문 상태 충돌: orderId={}, status={}", orderId, currentStatus);
            throw new OrderFulfillmentConflictException(
                    "주문 상태(" + currentStatus + ")에서 배송을 생성할 수 없습니다.");
        }

        // 3. 대상 항목 결정
        List<Long> assignedIds = shipmentRepository.findAssignedOrderItemIds(orderId);
        Set<Long> assignedSet = Set.copyOf(assignedIds);

        List<OrderItem> orderItems = order.getItems();
        Set<Long> orderItemIdSet = orderItems.stream()
                .map(OrderItem::getId)
                .collect(Collectors.toSet());

        List<Long> targetOrderItemIds;

        if (orderItemIds == null || orderItemIds.isEmpty()) {
            // 생략(null/빈): 미발송 항목 전부
            targetOrderItemIds = orderItems.stream()
                    .map(OrderItem::getId)
                    .filter(id -> !assignedSet.contains(id))
                    .toList();
        } else {
            // 지정: 각 id 검증 (부작용 전 throw)
            for (Long itemId : orderItemIds) {
                if (!orderItemIdSet.contains(itemId)) {
                    // 미존재 또는 타 주문 소속 → 400 입력 오류 (모순3)
                    log.warn("배송 항목 입력 오류 — 미존재/타 주문 소속: orderId={}, orderItemId={}", orderId, itemId);
                    throw new InvalidShipmentItemException(
                            "주문 항목 ID " + itemId + "은(는) 해당 주문에 속하지 않습니다.");
                }
                if (assignedSet.contains(itemId)) {
                    // 이미 배정 → 409 상태 충돌 (모순3)
                    log.warn("배송 항목 이미 배정됨: orderId={}, orderItemId={}", orderId, itemId);
                    throw new OrderFulfillmentConflictException(
                            "주문 항목 ID " + itemId + "은(는) 이미 다른 배송에 배정되었습니다.");
                }
            }
            targetOrderItemIds = orderItemIds;
        }

        // 대상 0건 → 409 (만들 배송 없음)
        if (targetOrderItemIds.isEmpty()) {
            log.warn("배송 생성 불가 — 미발송 항목 0건: orderId={}", orderId);
            throw new OrderFulfillmentConflictException(
                    "미발송 항목이 없어 배송을 생성할 수 없습니다.");
        }

        // orderItemId → productName/quantity 역참조 맵
        Map<Long, OrderItem> orderItemMap = orderItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));

        // 4. Shipment.preparing + ShipmentItem 생성 → save
        Shipment shipment = Shipment.preparing(orderId);
        for (Long itemId : targetOrderItemIds) {
            ShipmentItem shipmentItem = ShipmentItem.of(itemId);
            shipment.addItem(shipmentItem);
        }

        try {
            shipmentRepository.save(shipment);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반 (동시 생성에서 락 우회 경합) → 409 매핑
            log.warn("배송 항목 UNIQUE 위반 (동시 경합): orderId={}", orderId, e);
            throw new OrderFulfillmentConflictException(
                    "배송 항목이 이미 다른 배송에 배정되었습니다. 다시 시도해 주세요.");
        }

        // 5. rollup: paid → preparing (첫 배송 생성 시)
        if ("paid".equals(currentStatus)) {
            order.markPreparing();
            log.info("주문 rollup paid→preparing: orderId={}", orderId);
        }
        // 이미 preparing이면 markPreparing 멱등 no-op (호출 생략)

        // 6. ShipmentResponse 변환·반환
        List<ShipmentItemResponse> itemResponses = shipment.getItems().stream()
                .map(si -> {
                    OrderItem orderItem = orderItemMap.get(si.getOrderItemId());
                    return new ShipmentItemResponse(
                            si.getOrderItemId(),
                            orderItem != null ? orderItem.getProductName() : "",
                            orderItem != null ? orderItem.getQuantity() : 0
                    );
                })
                .toList();

        log.info("배송 생성 완료: orderId={}, shipmentId={}, itemCount={}",
                orderId, shipment.getId(), itemResponses.size());

        return new ShipmentResponse(
                shipment.getId(),
                orderId,
                shipment.getStatus(),
                itemResponses
        );
    }

    /**
     * 주문의 배송 목록 조회 — REST GET 및 admin facade에서 사용.
     *
     * <p>컨트롤러가 Repository를 직접 보지 않도록 서비스가 조회·변환을 담당한다(레이어 일관).
     * 미존재 주문이어도 빈 목록 반환 (404는 생성 경로에서만).
     *
     * <p>같은 orderId에 속한 배송들이므로 Order를 1회만 로드해 orderItemMap을 공유한다
     * (shipment마다 반복 findById 제거).
     *
     * @param orderId 주문 ID
     * @return 배송 목록 (없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    public List<ShipmentResponse> getShipments(long orderId) {
        List<Shipment> shipments = shipmentRepository.findByOrderId(orderId);
        if (shipments.isEmpty()) {
            return List.of();
        }

        // 주문을 1회만 로드해 모든 배송 변환에 재사용 (N+1 제거)
        Order order = orderRepository.findById(orderId).orElse(null);
        Map<Long, OrderItem> orderItemMap = order != null
                ? order.getItems().stream()
                        .collect(Collectors.toMap(OrderItem::getId, item -> item))
                : Map.of();

        return shipments.stream()
                .map(shipment -> toShipmentResponseWithDetails(shipment, orderItemMap))
                .toList();
    }

    /**
     * Shipment Entity → ShipmentResponse DTO 변환 (사전 로드된 orderItemMap 사용).
     *
     * <p>ShipmentItem은 orderItemId scalar만 보유하므로 호출자가 Order를 1회 로드한 후
     * orderItemMap을 전달한다 (shipment마다 반복 조회 금지).
     */
    private ShipmentResponse toShipmentResponseWithDetails(Shipment shipment,
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
