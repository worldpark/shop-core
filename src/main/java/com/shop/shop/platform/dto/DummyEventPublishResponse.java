package com.shop.shop.platform.dto;

import java.time.Instant;

/**
 * 더미 이벤트 발행 REST 응답 DTO.
 *
 * <p>Entity·이벤트를 직접 반환하지 않는 원칙에 따라 응답 전용 record로 분리한다.
 * Controller에서 이 DTO만 외부에 노출된다.
 *
 * @param eventId    발행된 이벤트의 고유 식별자
 * @param occurredAt 이벤트 발생 시각
 * @param message    발행 메시지
 * @param topic      Kafka 외부화 대상 토픽명
 */
public record DummyEventPublishResponse(
        String eventId,
        Instant occurredAt,
        String message,
        String topic
) {
}
