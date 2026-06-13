package com.shop.shop.platform.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Modulith Kafka 외부화 wire 포맷 회귀 테스트.
 *
 * <p>목적: {@link DummyOutboxSmokeEvent}를 {@code @Externalized} 경로(Outbox → externalization)로
 * 발행했을 때, Kafka wire 값이 plain JSON 오브젝트({...})인지 단언한다.
 * {@code JsonSerializer} 설정에서는 byte[]를 다시 base64로 이중 인코딩하므로 이 테스트가 실패하고,
 * {@code ByteArraySerializer} 설정에서는 통과한다.
 *
 * <p>구성:
 * <ul>
 *   <li>{@code @EmbeddedKafka} — 토픽 {@code shop-core-smoke-test} 사전 생성(auto-create 타이밍 flaky 방지)</li>
 *   <li>Testcontainers PostgreSQL — Outbox({@code event_publication})는 JPA-backed이므로 실 DataSource 필요</li>
 *   <li>기본 test yml의 전역 자동설정 제외를 빈 값으로 국소 리셋(CartServiceIntegrationTest 선례)</li>
 *   <li>{@code src/test/resources/application.yml}이 {@code src/main/resources/application.yml}을
 *       classpath shadow하므로 production app.yml의 serializer는 이 테스트에 자동 적용되지 않는다.
 *       따라서 {@code @TestPropertySource}로 {@code ByteArraySerializer}를 명시 고정한다.
 *       <br><b>production app.yml의 serializer 회귀는 {@code ProductionKafkaSerializerConfigTest}가 담당한다.</b></li>
 *   <li>이 테스트가 검증하는 것: 외부화 경로(ByteArrayJsonMessageConverter + ByteArraySerializer)가
 *       plain JSON wire를 만든다는 사실 — effective serializer를 명시 고정한 상태에서의 동작을 단언한다.</li>
 * </ul>
 *
 * <p>단언:
 * <ol>
 *   <li>wire 값 첫 비공백 문자가 {@code '{'} (plain JSON 오브젝트). {@code '"'}/base64 아님 → 이중 직렬화 회귀 가드</li>
 *   <li>{@code objectMapper.readValue(value, DummyOutboxSmokeEvent.class)} 성공 (역직렬화 가능)</li>
 *   <li>{@code eventId} 왕복 일치 (발행 이벤트 == 수신 wire 이벤트)</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EmbeddedKafka(topics = DummyOutboxSmokeEvent.TOPIC)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.modulith.events.externalization.enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // test resource application.yml이 main을 shadow하므로 ByteArraySerializer를 명시 고정한다.
        // 라이브러리 기본(ByteArraySerializer)에 암묵 의존하지 않고 전제를 코드로 못박는다.
        // production app.yml의 serializer 회귀 검증은 ProductionKafkaSerializerConfigTest가 담당.
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "shop.order.pending-expiry.enabled=false"
})
class OutboxKafkaWireFormatTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private com.shop.shop.platform.service.DummyEventPublishService dummyEventPublishService;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "wire-format-test-group-" + UUID.randomUUID(),
                "true",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        consumer = new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, DummyOutboxSmokeEvent.TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("(a) Outbox 외부화 wire 값 첫 문자가 '{' — plain JSON 오브젝트, base64/이중따옴표 아님")
    void externalized_event_wire_starts_with_open_brace() throws Exception {
        // given
        com.shop.shop.platform.event.DummyOutboxSmokeEvent published =
                publishSmokeEvent("wire-format-test-plain-json");

        // when — wire 수신 (raw StringDeserializer)
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));

        // then — (a) 첫 비공백 문자가 '{' (plain JSON object, NOT '"' or base64)
        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        String wireValue = records.iterator().next().value();
        assertThat(wireValue).isNotNull();

        String trimmed = wireValue.stripLeading();
        assertThat(trimmed.charAt(0))
                .as("wire 값 첫 문자는 '{' 이어야 한다(plain JSON). 이중 직렬화 시 '\"'(base64 래핑) 버그.")
                .isEqualTo('{');
    }

    @Test
    @DisplayName("(b) wire 값이 DummyOutboxSmokeEvent로 역직렬화 성공")
    void externalized_event_wire_deserializes_to_event() throws Exception {
        // given
        DummyOutboxSmokeEvent published = publishSmokeEvent("wire-format-test-deserialize");

        // when
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));

        // then — (b) objectMapper.readValue 성공
        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        String wireValue = records.iterator().next().value();

        assertThatCode(() -> objectMapper.readValue(wireValue, DummyOutboxSmokeEvent.class))
                .as("wire 값은 DummyOutboxSmokeEvent로 역직렬화 가능해야 한다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(c) wire 값의 eventId·message가 발행 이벤트와 왕복 일치")
    void externalized_event_wire_fields_roundtrip() throws Exception {
        // given
        String testMessage = "wire-roundtrip-" + UUID.randomUUID();
        DummyOutboxSmokeEvent published = publishSmokeEvent(testMessage);

        // when
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));

        // then — (c) 핵심 필드 왕복 일치
        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        String wireValue = records.iterator().next().value();

        DummyOutboxSmokeEvent received = objectMapper.readValue(wireValue, DummyOutboxSmokeEvent.class);

        assertThat(received.eventId())
                .as("eventId 왕복 일치")
                .isEqualTo(published.eventId());
        assertThat(received.message())
                .as("message 왕복 일치")
                .isEqualTo(testMessage);
        assertThat(received.occurredAt())
                .as("occurredAt 비null")
                .isNotNull();
    }

    /**
     * DummyEventPublishService를 @Transactional 경계 안에서 호출해 Outbox에 이벤트를 저장하고 커밋한다.
     * 커밋 후 Modulith externalization auto-config가 EmbeddedKafka로 외부화를 수행한다.
     */
    private DummyOutboxSmokeEvent publishSmokeEvent(String message) {
        return dummyEventPublishService.publish(message);
    }
}
