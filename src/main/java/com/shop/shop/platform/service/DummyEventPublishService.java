package com.shop.shop.platform.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.platform.event.DummyOutboxSmokeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 더미 이벤트 생성 및 트랜잭션 내 발행 서비스.
 *
 * <p>{@link ApplicationEventPublisher#publishEvent(Object)}를 {@code @Transactional} 범위 안에서 호출하여
 * Spring Modulith Event Publication Registry가 Outbox 레코드를 같은 트랜잭션에 원자적으로 저장한다.
 * 트랜잭션 커밋 후 {@code spring-modulith-events-kafka} 어댑터가 Kafka 외부화를 수행한다.
 *
 * <p>외부화 실패 시 {@code event_publication} 레코드가 INCOMPLETE로 남아 재시도·추적이 가능하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DummyEventPublishService {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 더미 이벤트를 생성하고 트랜잭션 내에서 발행한다.
     *
     * @param message 스모크 검증용 임의 메시지
     * @return 발행된 {@link DummyOutboxSmokeEvent}
     * @throws BusinessException 발행 중 예외 발생 시 커스텀 예외로 변환
     */
    @Transactional
    public DummyOutboxSmokeEvent publish(String message) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        DummyOutboxSmokeEvent event = new DummyOutboxSmokeEvent(eventId, occurredAt, message);

        log.info("더미 이벤트 발행 시도 — eventId={}, topic={}", eventId, DummyOutboxSmokeEvent.TOPIC);

        try {
            eventPublisher.publishEvent(event);
            log.info("더미 이벤트 발행 성공 — eventId={}, topic={}", eventId, DummyOutboxSmokeEvent.TOPIC);
        } catch (Exception e) {
            log.error("더미 이벤트 발행 실패 — eventId={}, topic={}", eventId, DummyOutboxSmokeEvent.TOPIC, e);
            throw new BusinessException(
                    "더미 이벤트 발행에 실패했습니다. eventId=" + eventId,
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }

        return event;
    }
}
