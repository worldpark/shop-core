package com.shop.shop.order.service;

import com.shop.shop.common.exception.AmountConversionException;
import com.shop.shop.common.exception.OrderNotFoundException;
import com.shop.shop.member.spi.MemberDirectory;
import com.shop.shop.member.spi.MemberDirectory.MemberContact;
import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.event.OrderCompletedEvent;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderConfirmation;
import com.shop.shop.product.spi.ProductOrderCatalog;
import com.shop.shop.product.spi.ProductOrderCatalog.OrderableVariantSnapshot;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link OrderConfirmation} 구현체 (package-private).
 *
 * <p>order 내부 {@code service} 패키지에 배치한다.
 * payment는 인터페이스({@link OrderConfirmation})만 참조하며, 이 구현체를 직접 알지 못한다(P1).
 *
 * <p>처리:
 * <ol>
 *   <li>orders row {@code PESSIMISTIC_WRITE} 비관락 (InventoryStockRepository 선례 동형)</li>
 *   <li>소유권 재검증 (불일치 → 404)</li>
 *   <li>status 분기 (이미 paid → 멱등 반환 / pending 외 → 409)</li>
 *   <li>금액 재검증 (불일치 → 409)</li>
 *   <li>Order.markPaid() 상태 전이</li>
 *   <li>OrderCompletedEvent 구성·발행 (같은 트랜잭션 Outbox 저장)</li>
 * </ol>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
class OrderConfirmationImpl implements OrderConfirmation {

    private final OrderRepository orderRepository;
    private final MemberDirectory memberDirectory;
    private final ProductOrderCatalog productOrderCatalog;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 락 후 fresh 재적재를 위한 EntityManager.
     *
     * <p>@RequiredArgsConstructor 생성자에서 제외하기 위해 final 미부여 + @Autowired @Lazy 필드 주입 사용.
     * @Lazy: test/resources/application.yml에서 JPA 자동설정이 제외된 슬라이스 테스트(CartRestControllerSecurityTest 등)가
     * 컨텍스트 로드 시 즉시 EntityManagerFactory를 조회하지 않도록 지연 초기화한다.
     * 실제 confirmPaid 호출 시 트랜잭션 범위 내에서 Spring이 EntityManager 프록시를 제공한다.
     *
     * <p>getPayableOrder가 OrderPaymentReaderImpl에서 managed Order(pending)를 pay 트랜잭션 영속성 컨텍스트에
     * 적재한 뒤 confirmPaid의 findByIdForUpdate가 PESSIMISTIC_WRITE 락을 잡아도 Hibernate가 1차 캐시의
     * stale 인스턴스를 그대로 반환하므로, 락 직후 refresh로 DB 최신 상태를 강제 재적재한다(033).
     */
    @Autowired
    @Lazy
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderConfirmationResult confirmPaid(long orderId, long requesterUserId, BigDecimal paidAmount) {
        // 1. orders row 비관락 (SELECT ... FOR UPDATE)
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(OrderNotFoundException::new);

        // 1-A(033): 락 직후 DB 최신 상태 강제 재적재 — stale 캐시 엔티티 덮어쓰기 방지
        // 원인: PaymentService.pay가 getPayableOrder로 Order(pending)를 영속성 컨텍스트에 먼저 적재하면
        // 이후 findByIdForUpdate가 PESSIMISTIC_WRITE 락은 잡지만 Hibernate 1차 캐시에 이미 존재하는
        // 동일 인스턴스(stale pending)를 반환한다(JPQL 결과가 managed 엔티티 상태를 덮어쓰지 않는 동작).
        // 취소 스레드가 먼저 커밋(pending→cancelled)해도 결제 스레드의 confirmPaid는 stale pending으로
        // status 판정해 markPaid로 덮어쓴다 → 락 후 refresh로 경쟁 트랜잭션의 최신 커밋 상태를 읽는다.
        // 순서 불변식: 락(SELECT FOR UPDATE) → refresh → 판정 (refresh를 락 전에 두면 재-race).
        entityManager.refresh(order);

        // 2. 소유권 재검증 (락 후 권위)
        if (!order.getUserId().equals(requesterUserId)) {
            throw new OrderNotFoundException();
        }

        // 3. status 분기
        String currentStatus = order.getStatus();
        if ("paid".equals(currentStatus)) {
            // 이미 paid → 멱등 반환 (재발행 없음)
            log.info("주문 이미 확정 — 멱등 반환: orderId={}, orderNumber={}", orderId, order.getOrderNumber());
            return new OrderConfirmationResult(
                    order.getId(),
                    order.getOrderNumber(),
                    OrderConfirmation.Outcome.ALREADY_CONFIRMED,
                    false,
                    order.getUpdatedAt(),
                    null
            );
        }

        if (!"pending".equals(currentStatus)) {
            // 비-pending 상태 → REJECTED 값 반환 (throw 대신 값으로 표현 — forward-compat)
            log.warn("주문 상태 충돌: orderId={}, status={}", orderId, currentStatus);
            return new OrderConfirmationResult(
                    order.getId(),
                    order.getOrderNumber(),
                    OrderConfirmation.Outcome.REJECTED,
                    false,
                    order.getUpdatedAt(),
                    "주문 상태(" + currentStatus + ")에서 결제 확정을 할 수 없습니다."
            );
        }

        // 4. 금액 재검증
        if (order.getFinalAmount().compareTo(paidAmount) != 0) {
            // 금액 불일치 → REJECTED 값 반환 (throw 대신 값으로 표현 — forward-compat)
            log.warn("결제 금액 불일치(확정 단계): orderId={}, orderAmount={}, paidAmount={}",
                    orderId, order.getFinalAmount(), paidAmount);
            return new OrderConfirmationResult(
                    order.getId(),
                    order.getOrderNumber(),
                    OrderConfirmation.Outcome.REJECTED,
                    false,
                    order.getUpdatedAt(),
                    "결제 금액이 주문 금액과 일치하지 않습니다."
            );
        }

        // 5. pending → paid 전이
        order.markPaid();

        // 6. OrderCompletedEvent 구성·발행
        OrderCompletedEvent event = buildOrderCompletedEvent(order);

        log.info("OrderCompletedEvent 발행 시도: eventId={}, topic={}, orderId={}",
                event.eventId(), OrderCompletedEvent.TOPIC, orderId);
        eventPublisher.publishEvent(event);
        log.info("OrderCompletedEvent 발행 성공: eventId={}, topic={}",
                event.eventId(), OrderCompletedEvent.TOPIC);

        return new OrderConfirmationResult(
                order.getId(),
                order.getOrderNumber(),
                OrderConfirmation.Outcome.CONFIRMED,
                true,
                Instant.now(),
                null
        );
    }

    /**
     * OrderCompletedEvent 페이로드 구성.
     *
     * <p>member 연락처는 member.spi, item productId는 product.spi 해석.
     * productName/quantity/unitPrice는 order_items 스냅샷 출처(P4 — 주문 후 product 변경 무영향).
     * 금액 변환: {@link #toLong(BigDecimal)} (longValueExact, 소수부 비0 → AmountConversionException).
     *
     * @param order 확정된 주문 (items lazy 로딩 허용 — 같은 트랜잭션)
     * @return 구성된 이벤트
     */
    private OrderCompletedEvent buildOrderCompletedEvent(Order order) {
        // member 연락처 조회 (member.spi)
        MemberContact contact = memberDirectory.findContactByUserId(order.getUserId());

        // item productId 해석 (product.spi) + 스냅샷 필드 구성
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

        List<OrderCompletedEvent.Item> eventItems = items.stream()
                .map(item -> {
                    Long productId = variantToProductId.get(item.getVariantId());
                    if (productId == null) {
                        // confirmPaid 단계에서 해석 불가 — getPayableOrder의 사전검증이 막았어야 함
                        // 시스템 불변식 위반으로 처리
                        throw new com.shop.shop.common.exception.PaymentEventResolutionException(
                                "주문 확정 중 productId 해석에 실패했습니다.");
                    }
                    return new OrderCompletedEvent.Item(
                            productId,
                            item.getProductName(),    // order_items 스냅샷 (P4)
                            item.getQuantity(),       // order_items 스냅샷 (P4)
                            toLong(item.getUnitPrice()) // order_items 스냅샷 + longValueExact (P3, P4)
                    );
                })
                .toList();

        return new OrderCompletedEvent(
                UUID.randomUUID(),
                Instant.now(),
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                contact.email(),
                contact.name(),
                eventItems,
                toLong(order.getFinalAmount()),  // P3
                "KRW",
                Instant.now()
        );
    }

    /**
     * BigDecimal → long 변환 (P3).
     *
     * <p>KRW는 소수 단위가 없으므로 소수부가 0이어야 한다.
     * {@code longValueExact()} 위반(소수부 비0 / long 범위 초과) →
     * {@link AmountConversionException}(500, 시스템 불변식 위반).
     *
     * @param amount 변환할 BigDecimal
     * @return long 값
     * @throws AmountConversionException 소수부 비0 또는 long 범위 초과
     */
    private long toLong(BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new AmountConversionException(
                    "금액 변환 실패(소수부 비0 또는 long 범위 초과): " + amount);
        }
    }
}
