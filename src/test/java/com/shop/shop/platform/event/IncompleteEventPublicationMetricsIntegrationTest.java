package com.shop.shop.platform.event;

import com.shop.shop.platform.service.DummyEventPublishService;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미완료 발행 게이지 통합 테스트 (Task 065).
 *
 * <p>목적: {@code IncompleteEventPublicationMetrics}가 {@code event_publication}의
 * 미완료(completion_date IS NULL) 건수를 MeterRegistry 게이지로 정확히 보고하는지 검증한다.
 *
 * <p>064 {@code OutboxScheduledResubmitIntegrationTest} 배선을 그대로 계승한다.
 * (같은 {@code platform/event} 패키지, 같은 Dummy 픽스처, 같은 {@code @TestPropertySource} 국소 리셋 패턴).
 *
 * <p>절차:
 * <ol>
 *   <li>발행 N건 → Awaitility로 {@code completion_date} 채워질 때까지 대기 → PK 캡처</li>
 *   <li>{@code UPDATE event_publication SET completion_date=NULL WHERE id=?::uuid}(자동커밋)로 N건 미완료화</li>
 *   <li>게이지 값 == N 단언</li>
 *   <li>{@code UPDATE event_publication SET completion_date=now() WHERE id=?::uuid}로 완료 처리</li>
 *   <li>게이지 값 == 0 단언</li>
 * </ol>
 *
 * <p>비-{@code @Transactional} 테스트 — JdbcTemplate.update 자동커밋이 게이지 평가 전에 반드시 커밋되어야 함.
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
        "shop.events.republish.enabled=false",    // 스케줄러 잡음 차단 — 게이지 평가 환경 격리
        "shop.events.metrics.enabled=true"        // 게이지 빈 ON (이 통합 테스트에서만 명시)
})
class IncompleteEventPublicationMetricsIntegrationTest {

    private static final int SEED_COUNT = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private DummyEventPublishService dummyEventPublishService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("미완료 N건 시드 후 게이지 == N, 완료 처리 후 게이지 == 0")
    void gauge_reflects_incomplete_count() {

        // ── (1) SEED_COUNT건 발행 → 완료 대기 → PK 캡처 ──
        List<String> pubIds = new ArrayList<>();
        for (int i = 0; i < SEED_COUNT; i++) {
            DummyOutboxSmokeEvent event = dummyEventPublishService.publish("metrics-seed-" + i);
            UUID eventId = event.eventId();

            // 완료 대기
            Awaitility.await()
                    .atMost(15, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        String completionDate = jdbcTemplate.queryForObject(
                                "SELECT completion_date::text FROM event_publication WHERE serialized_event LIKE ?",
                                String.class,
                                "%\"" + eventId + "\"%"
                        );
                        assertThat(completionDate).isNotNull();
                    });

            // PK 캡처
            String pubId = jdbcTemplate.queryForObject(
                    "SELECT id::text FROM event_publication WHERE serialized_event LIKE ?",
                    String.class,
                    "%\"" + eventId + "\"%"
            );
            assertThat(pubId).as("publication 행이 존재해야 한다 (eventId=" + eventId + ")").isNotNull();
            pubIds.add(pubId);
        }

        // ── (2) SEED_COUNT건 completion_date=NULL 로 강제(자동커밋) ──
        for (String pubId : pubIds) {
            int updated = jdbcTemplate.update(
                    "UPDATE event_publication SET completion_date = NULL WHERE id = ?::uuid",
                    pubId
            );
            assertThat(updated).as("UPDATE가 정확히 1건을 갱신해야 한다 (id=" + pubId + ")").isEqualTo(1);
        }

        // ── 단언 (a): 게이지 == SEED_COUNT ──
        // 게이지는 lazy 평가 — registry.get(...).gauge().value() 호출 시점에 count 쿼리 실행
        double gaugeValue = meterRegistry.get("shop.events.publication.incomplete").gauge().value();
        assertThat(gaugeValue)
                .as("미완료 %d건 시드 후 게이지 값이 %d이어야 한다".formatted(SEED_COUNT, SEED_COUNT))
                .isEqualTo(SEED_COUNT);

        // ── (3) 직접 UPDATE로 완료 처리(결정적) ──
        for (String pubId : pubIds) {
            int updated = jdbcTemplate.update(
                    "UPDATE event_publication SET completion_date = now() WHERE id = ?::uuid",
                    pubId
            );
            assertThat(updated).as("완료 UPDATE가 정확히 1건을 갱신해야 한다 (id=" + pubId + ")").isEqualTo(1);
        }

        // ── 단언 (b): 게이지 == 0 ──
        double gaugeAfterComplete = meterRegistry.get("shop.events.publication.incomplete").gauge().value();
        assertThat(gaugeAfterComplete)
                .as("완료 처리 후 게이지 값이 0이어야 한다")
                .isEqualTo(0d);
    }
}
