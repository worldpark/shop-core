package com.shop.shop.order.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 배송 시작 이벤트 — Transactional Outbox로 Kafka {@code shipping-started} 토픽에 외부화된다.
 *
 * <p>발행 소유: order 모듈 (package-structure-rule: ShippingStartedEvent는 order/event 소유).
 * 발행 경로: {@code OrderFulfillmentService}이 {@code ApplicationEventPublisher.publishEvent(event)}를
 * {@code @Transactional} 안에서 호출 → Spring Modulith Event Publication Registry가
 * {@code event_publication} INCOMPLETE 저장(Outbox) → 커밋 후 Kafka 외부화.
 *
 * <p>이벤트 계약 SSOT: docs/event-catalog.md(ShippingStartedEvent) — 020에서 배송 단위로 개정.
 * {@code shipmentId} + {@code items[]} 추가(주문 단위 → 배송 단위 개정).
 * 페이로드는 자족적(컨슈머가 shop-core를 역조회하지 않도록).
 *
 * <p>occurredAt = shippedAt (시각 1회 캡처, 개선4 — 미세 불일치 방지).
 *
 * @param eventId        이벤트 고유 식별자 (컨슈머 멱등 키)
 * @param occurredAt     이벤트 발생 시각 (shippedAt과 동일 값, 개선4)
 * @param orderId        주문 PK
 * @param orderNumber    사용자 노출용 주문번호
 * @param shipmentId     배송 PK (배송 단위 식별자, 020 신규)
 * @param memberId       주문 회원 PK
 * @param memberEmail    알림 수신 이메일
 * @param memberName     수신자 이름
 * @param carrier        택배사명
 * @param trackingNumber 운송장 번호
 * @param items          이 배송에 포함된 항목 목록 (주문 전체 아님, 020 신규)
 * @param shippedAt      배송 시작 시각
 */
@Externalized("shipping-started")
public record ShippingStartedEvent(
        UUID eventId,
        Instant occurredAt,
        long orderId,
        String orderNumber,
        long shipmentId,
        long memberId,
        String memberEmail,
        String memberName,
        String carrier,
        String trackingNumber,
        List<Item> items,
        Instant shippedAt
) {

    /** 외부화 대상 Kafka 토픽명. OrderCompletedEvent 선례와 동일 패턴. */
    public static final String TOPIC = "shipping-started";

    /**
     * 배송 항목 (이 배송에 포함된 항목 — order_items 스냅샷, shipment_items 기준).
     *
     * <p>unitPrice 없음 — 배송 이벤트 계약(event-catalog.md).
     * productId는 product.spi 해석값, productName/quantity는 order_items 스냅샷 출처.
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
