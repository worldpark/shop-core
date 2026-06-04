package com.shop.shop.platform.service;

import com.shop.shop.platform.dto.DummyEventPublishResponse;
import com.shop.shop.platform.event.DummyOutboxSmokeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link DummyEventServiceResponse} 단위 테스트.
 *
 * <p>{@link DummyEventPublishService}를 Mockito로 모킹하여
 * DTO 매핑 로직만 격리 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DummyEventServiceResponseTest {

    private static final String EXPECTED_TOPIC = "shop-core-smoke-test";

    @Mock
    private DummyEventPublishService dummyEventPublishService;

    @InjectMocks
    private DummyEventServiceResponse dummyEventServiceResponse;

    @Test
    @DisplayName("publishDummy() 반환 DTO의 eventId가 Service 반환 이벤트의 eventId와 일치한다")
    void publishDummy_maps_eventId_correctly() {
        UUID eventId = UUID.randomUUID();
        DummyOutboxSmokeEvent fakeEvent = new DummyOutboxSmokeEvent(eventId, Instant.now(), "test");
        when(dummyEventPublishService.publish(anyString())).thenReturn(fakeEvent);

        DummyEventPublishResponse response = dummyEventServiceResponse.publishDummy("test");

        assertThat(response.eventId()).isEqualTo(eventId.toString());
    }

    @Test
    @DisplayName("publishDummy() 반환 DTO의 occurredAt이 Service 반환 이벤트의 occurredAt과 일치한다")
    void publishDummy_maps_occurredAt_correctly() {
        Instant occurredAt = Instant.now();
        DummyOutboxSmokeEvent fakeEvent = new DummyOutboxSmokeEvent(UUID.randomUUID(), occurredAt, "test");
        when(dummyEventPublishService.publish(anyString())).thenReturn(fakeEvent);

        DummyEventPublishResponse response = dummyEventServiceResponse.publishDummy("test");

        assertThat(response.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("publishDummy() 반환 DTO의 message가 Service 반환 이벤트의 message와 일치한다")
    void publishDummy_maps_message_correctly() {
        String message = "smoke-message";
        DummyOutboxSmokeEvent fakeEvent = new DummyOutboxSmokeEvent(UUID.randomUUID(), Instant.now(), message);
        when(dummyEventPublishService.publish(anyString())).thenReturn(fakeEvent);

        DummyEventPublishResponse response = dummyEventServiceResponse.publishDummy(message);

        assertThat(response.message()).isEqualTo(message);
    }

    @Test
    @DisplayName("publishDummy() 반환 DTO의 topic이 'shop-core-smoke-test'이다")
    void publishDummy_sets_correct_topic() {
        DummyOutboxSmokeEvent fakeEvent = new DummyOutboxSmokeEvent(UUID.randomUUID(), Instant.now(), "test");
        when(dummyEventPublishService.publish(anyString())).thenReturn(fakeEvent);

        DummyEventPublishResponse response = dummyEventServiceResponse.publishDummy("test");

        assertThat(response.topic()).isEqualTo(EXPECTED_TOPIC);
    }
}
