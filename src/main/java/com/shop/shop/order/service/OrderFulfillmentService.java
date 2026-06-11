package com.shop.shop.order.service;

import com.shop.shop.common.exception.InvalidShipmentItemException;
import com.shop.shop.common.exception.OrderFulfillmentConflictException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.common.exception.ShipmentNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.Shipment;
import com.shop.shop.order.domain.ShipmentItem;
import com.shop.shop.order.dto.ShipmentItemResponse;
import com.shop.shop.order.dto.ShipmentResponse;
import com.shop.shop.order.event.ShippingStartedEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 주문 이행(Fulfillment) 서비스 — public 클래스(모순①).
 *
 * <p>{@code AdminOrderFulfillmentRestController}({@code order/controller})와
 * {@code AdminOrderFulfillmentFacadeImpl}({@code order/service})가 다른 패키지에서 직접 호출하므로
 * public이어야 한다.
 *
 * <p>의존: {@link OrderRepository}·{@link ShipmentRepository}·{@link MemberDirectory}·
 * {@link ProductOrderCatalog}·{@link ApplicationEventPublisher}.
 * 배송 시작은 member.spi(연락처 해석) + product.spi(productId 해석) + Outbox 이벤트 발행이 필요하다.
 * 새 cross-module 의존은 없으며 기존 단방향 의존(order → member.spi / order → product.spi)만 사용한다.
 *
 * <p>관리자 단일 주체 — 소유권 검사 불요 (ROLE_ADMIN 전역 권한 보장).
 * 미존재 주문은 평범한 404 반환 (관리자 경로 — 존재 은닉 불요).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderFulfillmentService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final MemberDirectory memberDirectory;
    private final ProductOrderCatalog productOrderCatalog;
    private final ApplicationEventPublisher eventPublisher;

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
                null, // carrier: preparing 단계 null
                null, // trackingNumber: preparing 단계 null
                null, // shippedAt: preparing 단계 null
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
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt(),
                itemResponses
        );
    }

    /**
     * 배송 시작 — 주문 row 비관락 + 상태 재검증 + shipment 전이 + rollup + ShippingStartedEvent 발행.
     *
     * <p>처리 흐름(정합1 락 순서):
     * <ol>
     *   <li>orderId 스칼라 projection {@link ShipmentRepository#findOrderIdById(long)} — 엔티티 적재 금지.
     *       empty → {@link ShipmentNotFoundException}(404)</li>
     *   <li>주문 row {@code PESSIMISTIC_WRITE} 잠금 — 동시 ship·018 취소와 직렬화</li>
     *   <li><b>락 후</b> shipment 엔티티 최초 적재({@code findById}) — 항상 fresh, stale read 방지</li>
     *   <li>상태 재검증:
     *     <ul>
     *       <li>주문 {@code cancelled}/{@code refunded} → {@link OrderFulfillmentConflictException}(409)</li>
     *       <li>방어 가드(개선5): 주문 status ∉ {preparing, shipping} → 409</li>
     *       <li>shipment 이미 {@code shipping} → 멱등 200(markShipping·이벤트 미실행, 기존 값 유지=정합4)</li>
     *       <li>shipment {@code delivered}/역방향 → 409</li>
     *       <li>{@code preparing}이면 진행</li>
     *     </ul>
     *   </li>
     *   <li>시각 1회 캡처(개선4): {@code Instant now} — shippedAt/occurredAt 동일 값</li>
     *   <li>P2 사전검증: variantId null → 409, 스냅샷 미반환(행 삭제) → 409.
     *       비활성·품절은 통과(정합5). 만든 variantId→productId 맵을 이벤트 빌더 재사용(개선1)</li>
     *   <li>전이: {@code shipment.markShipping(carrier, trackingNumber, now)} +
     *       rollup: 주문 preparing이면 {@code order.markShipping()}(이미 shipping이면 생략 — 멀티 배송)</li>
     *   <li>이벤트 구성·발행(Outbox)</li>
     *   <li>커밋 → 200 + 갱신 {@link ShipmentResponse}</li>
     * </ol>
     *
     * @param shipmentId     배송 ID
     * @param carrier        택배사명
     * @param trackingNumber 운송장 번호
     * @return 갱신된 배송 응답 DTO
     * @throws ShipmentNotFoundException         미존재 배송 (404)
     * @throws OrderFulfillmentConflictException 상태 충돌·취소/환불 주문·P2 해석 불가 (409)
     */
    public ShipmentResponse ship(long shipmentId, String carrier, String trackingNumber) {
        // 1. orderId 스칼라 projection (정합1 — 엔티티 적재 금지)
        long orderId = shipmentRepository.findOrderIdById(shipmentId)
                .orElseThrow(ShipmentNotFoundException::new);

        // 2. 주문 row PESSIMISTIC_WRITE 잠금 (동시 ship·018 취소와 직렬화)
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        // 3. 락 후 shipment 엔티티 최초 적재 (fresh — stale read 방지, 정합1)
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(ShipmentNotFoundException::new);

        // 4. 상태 재검증 (부작용 전 throw)
        String orderStatus = order.getStatus();
        if ("cancelled".equals(orderStatus) || "refunded".equals(orderStatus)) {
            log.warn("배송 시작 불가 — 취소/환불 주문: orderId={}, status={}", orderId, orderStatus);
            throw new OrderFulfillmentConflictException(
                    "취소 또는 환불된 주문의 배송을 시작할 수 없습니다.");
        }
        // 방어 가드(개선5): 정상 흐름에서 주문은 preparing/shipping이어야 함
        if (!"preparing".equals(orderStatus) && !"shipping".equals(orderStatus)) {
            log.warn("배송 시작 불가 — 불일치 주문 상태(방어 가드): orderId={}, status={}", orderId, orderStatus);
            throw new OrderFulfillmentConflictException(
                    "주문 상태(" + orderStatus + ")에서 배송을 시작할 수 없습니다.");
        }

        String shipmentStatus = shipment.getStatus();
        if ("shipping".equals(shipmentStatus)) {
            // 멱등: 이미 shipping → markShipping·이벤트 미실행, 기존 값 반환 (정합4)
            log.info("배송 이미 시작됨 — 멱등 반환: shipmentId={}, orderId={}", shipmentId, orderId);
            return buildShipmentResponse(shipment, order);
        }
        if (!"preparing".equals(shipmentStatus)) {
            // delivered/역방향 → 409
            log.warn("배송 시작 불가 — 잘못된 전이: shipmentId={}, status={}", shipmentId, shipmentStatus);
            throw new OrderFulfillmentConflictException(
                    "배송 상태(" + shipmentStatus + ")에서 배송을 시작할 수 없습니다.");
        }

        // 5. 시각 1회 캡처 (개선4 — shippedAt/occurredAt 동일 값)
        Instant now = Instant.now();

        // 6. P2 사전검증: variantId→productId 해석 (정합5 — 비활성·품절은 통과)
        List<ShipmentItem> shipmentItems = shipment.getItems();
        Map<Long, OrderItem> orderItemMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));

        // shipment 항목의 orderItem 목록
        List<OrderItem> targetOrderItems = shipmentItems.stream()
                .map(si -> orderItemMap.get(si.getOrderItemId()))
                .filter(item -> item != null)
                .toList();

        // variantId null 검증 (FK SET NULL — variant 행 삭제)
        boolean hasNullVariant = targetOrderItems.stream()
                .anyMatch(item -> item.getVariantId() == null);
        if (hasNullVariant) {
            log.warn("배송 시작 불가 — variantId null(P2): shipmentId={}", shipmentId);
            throw new OrderFulfillmentConflictException(
                    "배송 항목의 상품 정보를 해석할 수 없습니다(variantId null). 배송을 시작할 수 없습니다.");
        }

        List<Long> variantIds = targetOrderItems.stream()
                .map(OrderItem::getVariantId)
                .distinct()
                .toList();

        // 스냅샷 조회 (개선1 — 이벤트 빌더에 재사용)
        Map<Long, Long> variantToProductId;
        if (variantIds.isEmpty()) {
            variantToProductId = Map.of();
        } else {
            List<OrderableVariantSnapshot> snapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
            variantToProductId = snapshots.stream()
                    .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, OrderableVariantSnapshot::productId));
            // 스냅샷 미반환 = 행 삭제 → 409 (비활성·품절은 거부 안 함, 정합5)
            boolean hasMissingSnapshot = variantIds.stream()
                    .anyMatch(vid -> !variantToProductId.containsKey(vid));
            if (hasMissingSnapshot) {
                log.warn("배송 시작 불가 — 스냅샷 미반환(P2): shipmentId={}", shipmentId);
                throw new OrderFulfillmentConflictException(
                        "배송 항목의 상품 정보를 해석할 수 없습니다(variant 삭제됨). 배송을 시작할 수 없습니다.");
            }
        }

        // 7. 전이: preparing → shipping
        shipment.markShipping(carrier, trackingNumber, now);
        log.info("배송 전이 preparing→shipping: shipmentId={}, orderId={}", shipmentId, orderId);

        // rollup: 주문 preparing이면 shipping으로 (이미 shipping이면 생략 — 멀티 배송)
        if ("preparing".equals(orderStatus)) {
            order.markShipping();
            log.info("주문 rollup preparing→shipping: orderId={}", orderId);
        }

        // 8. 이벤트 구성·발행 (Outbox, 개선1 — variantToProductId 재사용)
        ShippingStartedEvent event = buildShippingStartedEvent(order, shipment, orderItemMap, variantToProductId, now);
        log.info("ShippingStartedEvent 발행 시도: eventId={}, topic={}, orderId={}, shipmentId={}",
                event.eventId(), ShippingStartedEvent.TOPIC, orderId, shipmentId);
        eventPublisher.publishEvent(event);
        log.info("ShippingStartedEvent 발행 성공: eventId={}, topic={}", event.eventId(), ShippingStartedEvent.TOPIC);

        // 9. 갱신된 ShipmentResponse 반환
        log.info("배송 시작 완료: orderId={}, shipmentId={}", orderId, shipmentId);
        return buildShipmentResponse(shipment, order);
    }

    /**
     * ShippingStartedEvent 페이로드 구성 (개선1 — variantToProductId 재사용).
     *
     * <p>member 연락처는 member.spi, item productId는 P2에서 만든 variantToProductId 맵 재사용.
     * productName/quantity는 order_items 스냅샷(주문 시점 값). items[]는 이 배송분만.
     * OrderConfirmationImpl.buildOrderCompletedEvent 동형.
     *
     * @param order              잠금 보유 주문 엔티티
     * @param shipment           상태 전이된 배송 엔티티
     * @param orderItemMap       orderItemId → OrderItem 맵
     * @param variantToProductId variantId → productId 맵 (P2에서 조회, 재사용)
     * @param now                시각 캡처 (shippedAt = occurredAt, 개선4)
     * @return 구성된 이벤트
     */
    private ShippingStartedEvent buildShippingStartedEvent(
            Order order, Shipment shipment,
            Map<Long, OrderItem> orderItemMap, Map<Long, Long> variantToProductId, Instant now) {

        // member 연락처 조회 (member.spi)
        MemberContact contact = memberDirectory.findContactByUserId(order.getUserId());

        // items[] 구성 (이 배송분만 — shipment.getItems())
        List<ShippingStartedEvent.Item> eventItems = shipment.getItems().stream()
                .map(si -> {
                    OrderItem orderItem = orderItemMap.get(si.getOrderItemId());
                    if (orderItem == null) {
                        // 시스템 불변식 위반 — shipment_items는 항상 order_items와 연결되어야 함
                        throw new com.shop.shop.common.exception.OrderFulfillmentConflictException(
                                "배송 항목과 주문 항목 매핑에 실패했습니다. 시스템 불변식 위반.");
                    }
                    Long productId = variantToProductId.get(orderItem.getVariantId());
                    if (productId == null) {
                        // P2 통과 후 발생 불가 — 방어적 처리
                        throw new com.shop.shop.common.exception.OrderFulfillmentConflictException(
                                "배송 항목의 productId 해석에 실패했습니다. 시스템 불변식 위반.");
                    }
                    return new ShippingStartedEvent.Item(
                            productId,
                            orderItem.getProductName(),
                            orderItem.getQuantity()
                    );
                })
                .toList();

        return new ShippingStartedEvent(
                UUID.randomUUID(),
                now,           // occurredAt = shippedAt (개선4)
                order.getId(),
                order.getOrderNumber(),
                shipment.getId(),
                order.getUserId(),
                contact.email(),
                contact.name(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                eventItems,
                now            // shippedAt = occurredAt (개선4)
        );
    }

    /**
     * Shipment + Order → ShipmentResponse DTO 변환 (ship 결과 반환용).
     *
     * <p>orderItemMap을 공유하기 위해 Order를 받아 처리한다.
     */
    private ShipmentResponse buildShipmentResponse(Shipment shipment, Order order) {
        Map<Long, OrderItem> orderItemMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item));

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

        return new ShipmentResponse(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getStatus(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getShippedAt(),
                itemResponses
        );
    }
}
