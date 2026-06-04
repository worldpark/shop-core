package com.shop.shop.platform.event;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox 스모크 검증용 더미 이벤트.
 *
 * <p>도메인 이벤트(OrderCompletedEvent / PaymentFailedEvent / ShippingStartedEvent)와 완전히 분리된
 * 인프라 검증 전용 이벤트다. 이름의 {@code Dummy} / {@code Smoke} 토큰이 도메인 이벤트와의 혼동을 방지한다.
 *
 * <p>{@code @Externalized}로 Kafka 토픽 {@code shop-core-smoke-test}에 외부화된다.
 * 이 토픽은 공개 이벤트 계약(docs/architecture.md 섹션 5) 외 스모크 전용 토픽이며,
 * notification 서비스가 구독하지 않는다.
 *
 * <p>비동기 외부화(커밋 후) 실패 시 {@code event_publication} 레코드가 INCOMPLETE로 남아
 * 재시도·추적이 가능하다. 스모크 범위에서는 로그+레코드 추적으로 충분하다.
 *
 * @param eventId    발행 식별자 (CLAUDE.md 이벤트 규칙: 멱등·추적용)
 * @param occurredAt 이벤트 발생 시각 (CLAUDE.md 이벤트 규칙: 발생 시각 포함)
 * @param message    스모크 검증용 임의 메시지
 */
@Externalized("shop-core-smoke-test")
public record DummyOutboxSmokeEvent(
        UUID eventId,
        Instant occurredAt,
        String message
) {
    /** 외부화 대상 Kafka 토픽명. Service·ServiceResponse의 SSOT로 사용한다. */
    public static final String TOPIC = "shop-core-smoke-test";
}
