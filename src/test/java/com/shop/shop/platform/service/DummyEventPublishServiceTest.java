package com.shop.shop.platform.service;

import com.shop.shop.platform.event.DummyOutboxSmokeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DummyEventPublishService} 단위 테스트.
 *
 * <p>DB / Kafka 불필요. {@link ApplicationEventPublisher}를 Mockito로 모킹하여
 * 발행 로직만 격리 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DummyEventPublishServiceTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DummyEventPublishService dummyEventPublishService;

    @Test
    @DisplayName("publish() 호출 시 eventId가 null이 아니다")
    void publish_sets_non_null_eventId() {
        DummyOutboxSmokeEvent result = dummyEventPublishService.publish("test-message");

        assertThat(result.eventId()).isNotNull();
    }

    @Test
    @DisplayName("publish() 호출 시 occurredAt이 null이 아니다")
    void publish_sets_non_null_occurredAt() {
        DummyOutboxSmokeEvent result = dummyEventPublishService.publish("test-message");

        assertThat(result.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publish() 호출 시 ApplicationEventPublisher.publishEvent()가 정확히 1회 호출된다")
    void publish_calls_publishEvent_exactly_once() {
        dummyEventPublishService.publish("test-message");

        verify(eventPublisher, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(DummyOutboxSmokeEvent.class));
    }

    @Test
    @DisplayName("publish() 호출 시 전달된 message가 이벤트에 포함된다")
    void publish_includes_given_message_in_event() {
        String expectedMessage = "hello-smoke";
        ArgumentCaptor<DummyOutboxSmokeEvent> captor = ArgumentCaptor.forClass(DummyOutboxSmokeEvent.class);

        dummyEventPublishService.publish(expectedMessage);

        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().message()).isEqualTo(expectedMessage);
    }
}
