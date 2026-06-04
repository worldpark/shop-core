package com.shop.shop.platform.service;

import com.shop.shop.platform.dto.DummyEventPublishResponse;
import com.shop.shop.platform.event.DummyOutboxSmokeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 더미 이벤트 발행 REST 응답 조합 계층 (ServiceResponse 역할).
 *
 * <p>REST 응답 조합 전용 계층이다. View / Scheduler / EventListener에서는 사용하지 않는다.
 * {@link DummyEventPublishService}를 호출하고 결과를 {@link DummyEventPublishResponse} DTO로 변환한다.
 *
 * <p>Controller는 이 계층만 의존하며 비즈니스 로직은 갖지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DummyEventServiceResponse {

    private final DummyEventPublishService dummyEventPublishService;

    /**
     * 더미 이벤트를 발행하고 결과를 응답 DTO로 반환한다.
     *
     * @param message 스모크 검증용 메시지
     * @return 발행 결과 DTO (eventId, occurredAt, message, topic 포함)
     */
    public DummyEventPublishResponse publishDummy(String message) {
        DummyOutboxSmokeEvent event = dummyEventPublishService.publish(message);
        return new DummyEventPublishResponse(
                event.eventId().toString(),
                event.occurredAt(),
                event.message(),
                DummyOutboxSmokeEvent.TOPIC
        );
    }
}
