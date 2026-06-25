package com.shop.shop.platform.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modulith 미완료 발행 재기동 재발행 회복 통합 테스트 (Task 063).
 *
 * <p>목적: {@code spring.modulith.events.republish-outstanding-events-on-restart=true} 설정 하에서
 * {@code event_publication}의 미완료(INCOMPLETE, completion_date IS NULL) 행이
 * {@link IncompleteEventPublications#resubmitIncompletePublications(java.util.function.Predicate)}를
 * 통해 Kafka로 재외부화되고 {@code completion_date}가 채워짐을 검증한다.
 *
 * <p>절차 (Plan §7.3):
 * <ol>
 *   <li>DummyEventPublishService.publish → Awaitility로 Kafka 1건 + 완료 확인</li>
 *   <li>해당 publication PK(uuid) 캡처 → UPDATE ... SET completion_date=NULL(자동커밋)</li>
 *   <li>컨슈머 레코드 비움 → 재발행만 깨끗이 관측</li>
 *   <li>IncompleteEventPublications.resubmitIncompletePublications(p-&gt;true) 호출</li>
 * </ol>
 *
 * <p>단언 (Plan §7.4):
 * <ul>
 *   <li>(a) 재제출 후 Kafka에 해당 eventId 재도착</li>
 *   <li>(b) 해당 PK의 completion_date IS NOT NULL</li>
 *   <li>(c) 완료분 비재발행 — 사전 완료된 별도 이벤트는 재제출 후 추가 도착 0건</li>
 * </ul>
 *
 * <p>배선: {@link OutboxKafkaWireFormatTest}의 @TestPropertySource 국소 리셋 계승.
 * 비-{@code @Transactional} 테스트 — JdbcTemplate.update 자동커밋이 resubmit 전에 반드시 커밋되어야 함(Plan §7.3).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EmbeddedKafka(topics = DummyOutboxSmokeEvent.TOPIC)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.modulith.events.externalization.enabled=true",
        "spring.modulith.events.republish-outstanding-events-on-restart=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "shop.order.pending-expiry.enabled=false"
})
class OutboxRepublishOnRestartIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private com.shop.shop.platform.service.DummyEventPublishService dummyEventPublishService;

    @Autowired
    private IncompleteEventPublications incompleteEventPublications;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "republish-test-group-" + UUID.randomUUID(),
                "true",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
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
    @DisplayName("미완료 publication을 재제출하면 Kafka 재도착 + completion_date 채워짐 + 완료분 비재발행")
    void resubmit_incomplete_publication_republishes_to_kafka_and_records_completion() throws Exception {

        // ── (사전) 완료분 비재발행 가드용 별도 이벤트 정상 발행 (Plan §7.4-(c)) ──
        DummyOutboxSmokeEvent completedEvent = dummyEventPublishService.publish("completed-guard-event");
        UUID completedEventId = completedEvent.eventId();

        // 완료 이벤트가 Kafka에 도착해 completion_date가 채워질 때까지 대기
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication "
                                    + "WHERE serialized_event LIKE ?",
                            String.class,
                            "%\"" + completedEventId + "\"%"
                    );
                    assertThat(completionDate).isNotNull();
                });

        // 완료 이벤트의 PK(id) 캡처 — 재제출 후 추가 도착 0건을 검증하기 위해
        String completedPubId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM event_publication WHERE serialized_event LIKE ?",
                String.class,
                "%\"" + completedEventId + "\"%"
        );
        assertThat(completedPubId).isNotNull();

        // ── (1) 정상 발행(베이스라인) — 이 이벤트를 INCOMPLETE로 되돌릴 것 ──
        DummyOutboxSmokeEvent targetEvent = dummyEventPublishService.publish("incomplete-target-event");
        UUID targetEventId = targetEvent.eventId();

        // 외부화 완료 대기 (Kafka 1건 수신 + completion_date 채워짐)
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication "
                                    + "WHERE serialized_event LIKE ?",
                            String.class,
                            "%\"" + targetEventId + "\"%"
                    );
                    assertThat(completionDate).isNotNull();
                });

        // ── (2) 해당 publication PK 캡처 ──
        String targetPubId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM event_publication WHERE serialized_event LIKE ?",
                String.class,
                "%\"" + targetEventId + "\"%"
        );
        assertThat(targetPubId).as("target publication 행이 존재해야 한다").isNotNull();

        // ── (3) UPDATE로 completion_date=NULL (INCOMPLETE 상태로 시드) ──
        // 비-@Transactional 컨텍스트: JdbcTemplate.update는 자동커밋 → resubmit가 DB 재조회 시 이 행을 본다
        int updated = jdbcTemplate.update(
                "UPDATE event_publication SET completion_date = NULL WHERE id = ?::uuid",
                targetPubId
        );
        assertThat(updated).as("UPDATE가 정확히 1건을 갱신해야 한다").isEqualTo(1);

        // 실제로 NULL이 됐는지 확인
        String nullCheck = jdbcTemplate.queryForObject(
                "SELECT completion_date::text FROM event_publication WHERE id = ?::uuid",
                String.class,
                targetPubId
        );
        assertThat(nullCheck).as("completion_date가 NULL로 시드되어야 한다").isNull();

        // ── (4) 컨슈머 레코드 비움 — 1·2단계에서 도착한 레코드 소비 ──
        // poll로 버퍼를 비워 재발행 도착만 깨끗이 관측
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));

        // ── (5) 재발행 트리거 (기동 afterSingletonsInstantiated()와 동일 코드 경로) ──
        incompleteEventPublications.resubmitIncompletePublications(p -> true);

        // ── 단언 (a): Kafka 재도착 — targetEventId가 포함된 레코드가 도착해야 한다 ──
        List<String> arrivedEventIds = new ArrayList<>();
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records =
                            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));
                    for (var record : records) {
                        String value = record.value();
                        if (value != null) {
                            try {
                                DummyOutboxSmokeEvent received =
                                        objectMapper.readValue(value, DummyOutboxSmokeEvent.class);
                                arrivedEventIds.add(received.eventId().toString());
                            } catch (Exception ignored) {
                                // 다른 포맷 레코드 무시
                            }
                        }
                    }
                    assertThat(arrivedEventIds)
                            .as("재제출 후 targetEventId가 Kafka에 도착해야 한다")
                            .contains(targetEventId.toString());
                });

        // ── 단언 (b): completion_date IS NOT NULL (회복 완료) ──
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication WHERE id = ?::uuid",
                            String.class,
                            targetPubId
                    );
                    assertThat(completionDate)
                            .as("재제출 후 completion_date가 채워져야 한다(회복 완료)")
                            .isNotNull();
                });

        // ── 단언 (c): 완료분 비재발행 — completedEvent는 재제출 후 추가 도착 없어야 한다 ──
        // arrivedEventIds 목록에 completedEventId가 없음을 확인
        // (재제출 대상은 INCOMPLETE 행뿐 — completion_date가 채워진 completedPubId 행은 제외)
        assertThat(arrivedEventIds)
                .as("이미 완료된 completedEvent는 재제출 후 추가로 Kafka에 도착하지 않아야 한다(완료분 비재발행)")
                .doesNotContain(completedEventId.toString());
    }
}
