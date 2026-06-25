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
 * 미완료 발행 상시 회복 스케줄러 통합 테스트 (Task 064).
 *
 * <p>목적: {@code IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration)}이
 * older-than 초과 미완료 발행만 Kafka 재외부화 + {@code completion_date} 기록하고,
 * older-than 미달 미완료 발행은 재제출하지 않음을 검증한다.
 *
 * <p>063의 {@code OutboxRepublishOnRestartIntegrationTest} 배선을 그대로 계승한다.
 * (같은 {@code platform/event} 패키지, 같은 Dummy 픽스처, 같은 {@code @TestPropertySource} 국소 리셋 패턴).
 *
 * <p>절차:
 * <ol>
 *   <li><b>older-than 초과 미완료 시드</b>: 발행 → 완료 대기 → PK 캡처 →
 *       {@code UPDATE ... SET completion_date=NULL, publication_date=now()-interval '10 minutes'}(자동커밋)</li>
 *   <li><b>older-than 미달 미완료 시드</b>: 발행 → 완료 대기 → PK 캡처 →
 *       {@code UPDATE ... SET completion_date=NULL}(publication_date 현재 유지, 자동커밋)</li>
 *   <li><b>완료분 불변 가드</b>: 발행 → 완료 상태 그대로 유지</li>
 *   <li>컨슈머 버퍼 비움</li>
 *   <li>{@code resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(2))} 직접 호출(리더가드 우회)</li>
 * </ol>
 *
 * <p>단언:
 * <ul>
 *   <li>(a) older-than 초과 건 Kafka 재도착 + completion_date 채워짐</li>
 *   <li>(b) older-than 미달 건 재도착 0 + completion_date 여전히 NULL(경계 단언 — 064 고유)</li>
 *   <li>(c) 완료분 재도착 0</li>
 * </ul>
 *
 * <p>비-{@code @Transactional} 테스트 — JdbcTemplate.update 자동커밋이 resubmit 전에 반드시 커밋되어야 함.
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
        "shop.order.pending-expiry.enabled=false",
        "shop.events.republish.enabled=false"   // 스케줄러 백그라운드 발화 잡음 방지 — 통합은 resubmit 직접 호출로 검증
})
class OutboxScheduledResubmitIntegrationTest {

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
                "scheduled-resubmit-test-group-" + UUID.randomUUID(),
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
    @DisplayName("older-than 초과 미완료만 재제출됨 + 미달 건 미대상 + 완료분 불변")
    void resubmitOlderThan_only_aged_incomplete_republished() throws Exception {

        // ── (1) older-than 초과 미완료 시드 ──
        DummyOutboxSmokeEvent agedEvent = dummyEventPublishService.publish("aged-incomplete-target");
        UUID agedEventId = agedEvent.eventId();

        // 완료 대기
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication WHERE serialized_event LIKE ?",
                            String.class,
                            "%\"" + agedEventId + "\"%"
                    );
                    assertThat(completionDate).isNotNull();
                });

        // PK 캡처
        String agedPubId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM event_publication WHERE serialized_event LIKE ?",
                String.class,
                "%\"" + agedEventId + "\"%"
        );
        assertThat(agedPubId).as("aged publication 행이 존재해야 한다").isNotNull();

        // completion_date=NULL + publication_date를 10분 과거로 (older-than=PT2M 초과)
        // 비-@Transactional 컨텍스트: JdbcTemplate.update는 자동커밋 → resubmit가 DB 재조회 시 이 행을 본다
        int agedUpdated = jdbcTemplate.update(
                "UPDATE event_publication "
                        + "SET completion_date = NULL, publication_date = now() - interval '10 minutes' "
                        + "WHERE id = ?::uuid",
                agedPubId
        );
        assertThat(agedUpdated).as("aged UPDATE가 정확히 1건을 갱신해야 한다").isEqualTo(1);

        // ── (2) older-than 미달 미완료 시드 (경계 가드) ──
        DummyOutboxSmokeEvent recentEvent = dummyEventPublishService.publish("recent-incomplete-target");
        UUID recentEventId = recentEvent.eventId();

        // 완료 대기
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication WHERE serialized_event LIKE ?",
                            String.class,
                            "%\"" + recentEventId + "\"%"
                    );
                    assertThat(completionDate).isNotNull();
                });

        // PK 캡처
        String recentPubId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM event_publication WHERE serialized_event LIKE ?",
                String.class,
                "%\"" + recentEventId + "\"%"
        );
        assertThat(recentPubId).as("recent publication 행이 존재해야 한다").isNotNull();

        // completion_date=NULL만 (publication_date는 현재 그대로 — older-than 미달)
        int recentUpdated = jdbcTemplate.update(
                "UPDATE event_publication SET completion_date = NULL WHERE id = ?::uuid",
                recentPubId
        );
        assertThat(recentUpdated).as("recent UPDATE가 정확히 1건을 갱신해야 한다").isEqualTo(1);

        // ── (3) 완료분 불변 가드 ──
        DummyOutboxSmokeEvent completedEvent = dummyEventPublishService.publish("completed-guard-event");
        UUID completedEventId = completedEvent.eventId();

        // 완료 대기 (completion_date 채워짐 — 재제출 미대상)
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication WHERE serialized_event LIKE ?",
                            String.class,
                            "%\"" + completedEventId + "\"%"
                    );
                    assertThat(completionDate).isNotNull();
                });

        // ── (4) 컨슈머 버퍼 비움 — 시드 과정에서 도착한 레코드 소비 ──
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));

        // ── (5) resubmitIncompletePublicationsOlderThan 직접 호출 (리더가드 우회) ──
        incompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(2));

        // ── 단언 (a): older-than 초과 건 Kafka 재도착 ──
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
                            .as("재제출 후 agedEventId가 Kafka에 도착해야 한다")
                            .contains(agedEventId.toString());
                });

        // ── 단언 (a) continued: completion_date 채워짐 (회복 완료) ──
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String completionDate = jdbcTemplate.queryForObject(
                            "SELECT completion_date::text FROM event_publication WHERE id = ?::uuid",
                            String.class,
                            agedPubId
                    );
                    assertThat(completionDate)
                            .as("재제출 후 aged 건의 completion_date가 채워져야 한다(회복 완료)")
                            .isNotNull();
                });

        // ── 단언 (b): older-than 미달 건 재도착 0 + completion_date 여전히 NULL (경계 단언) ──
        assertThat(arrivedEventIds)
                .as("older-than 미달(recent) 건은 재제출 대상이 아니므로 Kafka에 도착하지 않아야 한다")
                .doesNotContain(recentEventId.toString());

        String recentCompletionDate = jdbcTemplate.queryForObject(
                "SELECT completion_date::text FROM event_publication WHERE id = ?::uuid",
                String.class,
                recentPubId
        );
        assertThat(recentCompletionDate)
                .as("older-than 미달 건의 completion_date는 여전히 NULL이어야 한다(미대상)")
                .isNull();

        // ── 단언 (c): 완료분 재도착 0 ──
        assertThat(arrivedEventIds)
                .as("이미 완료된 건은 재제출 대상이 아니므로 Kafka에 도착하지 않아야 한다")
                .doesNotContain(completedEventId.toString());
    }
}
