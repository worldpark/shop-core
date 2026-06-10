package com.shop.shop.order.service;

import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.inventory.spi.InventoryStockPort;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.event.OrderCancelledEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderCancellation;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link OrderCancellation} 구현체 (package-private).
 *
 * <p>order 내부 {@code service} 패키지에 배치한다.
 * payment는 인터페이스({@link OrderCancellation})만 참조하며, 이 구현체를 직접 알지 못한다(P1).
 * {@link OrderConfirmationImpl}과 대칭 설계 — 같은 락 패턴·404 재검증·Outcome 값.
 *
 * <p>처리:
 * <ol>
 *   <li>orders row {@code PESSIMISTIC_WRITE} 비관락 + 소유권 재검증</li>
 *   <li>status 분기 (ALREADY_CANCELLED/REJECTED/진행)</li>
 *   <li>종결 전이 (markRefunded 또는 markCancelled, #3)</li>
 *   <li>재고 복원 (variantId 오름차순 increase, null skip+log)</li>
 *   <li>OrderCancelledEvent 구성·발행 (Outbox)</li>
 * </ol>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
class OrderCancellationImpl implements OrderCancellation {

    private static final String CURRENCY_KRW = "KRW";

    private final OrderRepository orderRepository;
    private final InventoryStockPort inventoryStockPort;
    private final MemberDirectory memberDirectory;
    private final ProductOrderCatalog productOrderCatalog;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderCancellationResult cancel(long orderId, long requesterUserId, RefundInfo refundInfo) {
        // 1. orders row 비관락 (locked reader가 이미 잡은 락 재진입 — 같은 트랜잭션)
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        // 2. 소유권 재검증 (락 후 권위)
        if (!order.getUserId().equals(requesterUserId)) {
            throw new OrderNotFoundException();
        }

        String currentStatus = order.getStatus();

        // 3. status 분기
        // 이미 종결 → 멱등 반환 (재복원·재발행 없음)
        if ("cancelled".equals(currentStatus) || "refunded".equals(currentStatus)) {
            log.info("주문 이미 취소/환불 — 멱등 반환: orderId={}, status={}", orderId, currentStatus);
            return new OrderCancellationResult(
                    order.getId(),
                    order.getOrderNumber(),
                    Outcome.ALREADY_CANCELLED,
                    currentStatus,
                    false,
                    null,
                    null
            );
        }

        // 이행단계 → REJECTED (정상 흐름엔 발생하지 않음 — PaymentService 2단계에서 선판정)
        if ("preparing".equals(currentStatus) || "shipping".equals(currentStatus) || "delivered".equals(currentStatus)) {
            log.warn("주문 이행단계 취소 불가(OrderCancellation 내부): orderId={}, status={}", orderId, currentStatus);
            return new OrderCancellationResult(
                    order.getId(),
                    order.getOrderNumber(),
                    Outcome.REJECTED,
                    currentStatus,
                    false,
                    null,
                    "주문 상태(" + currentStatus + ")에서 취소할 수 없습니다."
            );
        }

        Instant cancelledAt = Instant.now();

        // 4. 종결 전이 (#3: refunded 동반=markRefunded→refunded / 미결제=markCancelled→cancelled)
        if (refundInfo.refunded()) {
            order.markRefunded();  // paid → refunded
        } else {
            order.markCancelled(); // pending → cancelled
        }

        // 5. 재고 복원 — variantId 오름차순, null skip+log (best-effort)
        List<OrderItem> items = order.getItems();
        List<OrderItem> sortedItems = items.stream()
                .filter(item -> {
                    if (item.getVariantId() == null) {
                        log.warn("재고 복원 skip(variantId null): orderId={}, productName={}", orderId, item.getProductName());
                        return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparingLong(OrderItem::getVariantId))
                .toList();

        for (OrderItem item : sortedItems) {
            inventoryStockPort.increase(item.getVariantId(), item.getQuantity());
        }

        // 6. OrderCancelledEvent 구성·발행
        OrderCancelledEvent event = buildOrderCancelledEvent(order, refundInfo, cancelledAt);

        log.info("OrderCancelledEvent 발행 시도: eventId={}, topic={}, orderId={}",
                event.eventId(), OrderCancelledEvent.TOPIC, orderId);
        eventPublisher.publishEvent(event);
        log.info("OrderCancelledEvent 발행 성공: eventId={}, topic={}",
                event.eventId(), OrderCancelledEvent.TOPIC);

        return new OrderCancellationResult(
                order.getId(),
                order.getOrderNumber(),
                Outcome.CANCELLED,
                order.getStatus(),
                true,
                cancelledAt,
                null
        );
    }

    /**
     * OrderCancelledEvent 페이로드 구성.
     *
     * <p>member 연락처는 member.spi, item productId는 product.spi 해석.
     * productName/quantity는 order_items 스냅샷 출처(주문 시점 값, 이후 변경 무영향).
     * 삭제된 variant(variantId null) 항목은 이벤트 items에서 제외하고 로깅.
     * @param order       취소된 주문 (items lazy 로딩 허용 — 같은 트랜잭션)
     * @param refundInfo  환불 정보
     * @param cancelledAt 취소 처리 시각
     * @return 구성된 이벤트
     */
    private OrderCancelledEvent buildOrderCancelledEvent(Order order, RefundInfo refundInfo, Instant cancelledAt) {
        // member 연락처 조회 (member.spi)
        MemberContact contact = memberDirectory.findContactByUserId(order.getUserId());

        // item productId 해석 (product.spi) — null variant 제외
        List<OrderItem> items = order.getItems();
        List<Long> variantIds = items.stream()
                .map(OrderItem::getVariantId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<Long, Long> variantToProductId;
        if (variantIds.isEmpty()) {
            variantToProductId = Map.of();
        } else {
            List<OrderableVariantSnapshot> snapshots = productOrderCatalog.getOrderableSnapshots(variantIds);
            variantToProductId = snapshots.stream()
                    .collect(Collectors.toMap(OrderableVariantSnapshot::variantId, OrderableVariantSnapshot::productId));
        }

        List<OrderCancelledEvent.Item> eventItems = items.stream()
                .filter(item -> item.getVariantId() != null)
                .map(item -> {
                    Long productId = variantToProductId.get(item.getVariantId());
                    if (productId == null) {
                        // productId 해석 불가 — 이벤트 items에서 제외하고 로깅 (best-effort)
                        log.warn("OrderCancelledEvent items 제외 — productId 해석 불가: orderId={}, variantId={}",
                                order.getId(), item.getVariantId());
                        return null;
                    }
                    return new OrderCancelledEvent.Item(
                            productId,
                            item.getProductName(),
                            item.getQuantity()
                    );
                })
                .filter(item -> item != null)
                .toList();

        return new OrderCancelledEvent(
                UUID.randomUUID(),
                Instant.now(),
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                contact.email(),
                contact.name(),
                eventItems,
                refundInfo.refunded(),
                refundInfo.refundedAmount(),
                refundInfo.currency(),
                cancelledAt
        );
    }

}
