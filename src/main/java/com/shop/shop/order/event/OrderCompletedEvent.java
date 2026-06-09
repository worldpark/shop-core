package com.shop.shop.order.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 주문 확정 이벤트 — Transactional Outbox로 Kafka {@code order-completed} 토픽에 외부화된다.
 *
 * <p>발행 소유: order 모듈 (package-structure-rule: OrderCompletedEvent는 order/event 소유).
 * 발행 경로: {@code OrderConfirmationImpl}이 {@code ApplicationEventPublisher.publishEvent(event)}를
 * {@code @Transactional} 안에서 호출 → Spring Modulith Event Publication Registry가
 * {@code event_publication} INCOMPLETE 저장(Outbox) → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(OrderCompletedEvent) — 무변경.
 * 페이로드는 자족적(컨슈머가 shop-core를 역조회하지 않도록).
 *
 * @param eventId      이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt   이벤트 발생 시각
 * @param orderId      주문 PK
 * @param orderNumber  사용자 노출용 주문번호
 * @param memberId     주문 회원 PK
 * @param memberEmail  알림 수신 이메일
 * @param memberName   수신자 이름
 * @param items        주문 항목 목록
 * @param totalAmount  결제 총액 (long, KRW=원, event-catalog 계약)
 * @param currency     통화 코드 (예: "KRW")
 * @param orderedAt    주문 확정 시각
 */
@Externalized("order-completed")
public record OrderCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        long orderId,
        String orderNumber,
        long memberId,
        String memberEmail,
        String memberName,
        List<Item> items,
        long totalAmount,
        String currency,
        Instant orderedAt
) {

    /** 외부화 대상 Kafka 토픽명. DummyOutboxSmokeEvent 선례와 동일 패턴. */
    public static final String TOPIC = "order-completed";

    /**
     * 주문 항목 (order_items 스냅샷 — 주문 시점 값, 이후 product 변경 무영향).
     *
     * @param productId   상품 PK (product.spi 해석값)
     * @param productName 상품명 (주문 시점 스냅샷)
     * @param quantity    수량 (주문 시점 스냅샷)
     * @param unitPrice   단가 (long, KRW=원, 주문 시점 스냅샷)
     */
    public record Item(
            long productId,
            String productName,
            int quantity,
            long unitPrice
    ) {}
}
