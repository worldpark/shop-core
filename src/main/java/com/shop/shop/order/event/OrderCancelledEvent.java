package com.shop.shop.order.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 주문 취소 이벤트 — Transactional Outbox로 Kafka {@code order-cancelled} 토픽에 외부화된다.
 *
 * <p>발행 소유: order 모듈 (package-structure-rule: OrderCancelledEvent는 order/event 소유).
 * 발행 경로: {@code OrderCancellationImpl}이 {@code ApplicationEventPublisher.publishEvent(event)}를
 * {@code @Transactional} 안에서 호출 → Spring Modulith Event Publication Registry가
 * {@code event_publication} INCOMPLETE 저장(Outbox) → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(OrderCancelledEvent) — 018에서 신규 추가.
 * 페이로드는 자족적(컨슈머가 shop-core를 역조회하지 않도록).
 * 삭제된 variant(order_item.variant_id == null) 항목은 이벤트 items에서 제외하고 로깅.
 *
 * @param eventId         이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt      이벤트 발생 시각
 * @param orderId         주문 PK
 * @param orderNumber     사용자 노출용 주문번호
 * @param memberId        주문 회원 PK
 * @param memberEmail     알림 수신 이메일
 * @param memberName      수신자 이름
 * @param items           취소 항목 목록 (삭제된 variant 제외)
 * @param refunded        환불 여부 (결제완료 취소=true, 미결제 취소=false)
 * @param refundedAmount  환불 금액 (long, KRW=원). 미결제 취소=0
 * @param currency        통화 코드 (예: "KRW")
 * @param cancelledAt     취소 처리 시각
 */
@Externalized("order-cancelled")
public record OrderCancelledEvent(
        UUID eventId,
        Instant occurredAt,
        long orderId,
        String orderNumber,
        long memberId,
        String memberEmail,
        String memberName,
        List<Item> items,
        boolean refunded,
        long refundedAmount,
        String currency,
        Instant cancelledAt
) {

    /** 외부화 대상 Kafka 토픽명. OrderCompletedEvent 선례와 동일 패턴. */
    public static final String TOPIC = "order-cancelled";

    /**
     * 취소 항목 (order_items 스냅샷 — 주문 시점 값, 이후 product 변경 무영향).
     * unitPrice는 취소 이벤트 스키마에 포함하지 않는다(event-catalog 스키마 그대로).
     *
     * @param productId   상품 PK (product.spi 해석값)
     * @param productName 상품명 (주문 시점 스냅샷)
     * @param quantity    수량 (주문 시점 스냅샷)
     */
    public record Item(
            long productId,
            String productName,
            int quantity
    ) {}
}
