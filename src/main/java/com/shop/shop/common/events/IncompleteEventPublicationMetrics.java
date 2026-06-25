package com.shop.shop.common.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 미완료 이벤트 발행 관측 메트릭 (Task 065).
 *
 * <p>{@code event_publication}의 미완료(completion_date IS NULL) 건수를 Micrometer 게이지로 노출한다.
 * 063(재기동 회복)·064(스케줄 재제출)가 적체를 비우는지, 독성 메시지가 무한 적체되는지의 측정 축.
 *
 * <p>평가는 lazy — Prometheus 스크레이프 시점에 count 쿼리를 1회 실행한다(주기 캐시 없음).
 * 공통 태그 application=shop-core는 management.metrics.tags(application.yml)에서 전역 상속.
 *
 * <p>게이팅: 메모리(shop-core-tests-no-active-profile-gating)에 따라 @Profile 금지.
 * @ConditionalOnProperty(havingValue="true")로 운영 yml에서 명시 ON, 키 미설정 테스트
 * 컨텍스트에선 빈 미생성(064 IncompletePublicationResubmitScheduler와 동일 대칭).
 *
 * <p>다중 노드 해석: 각 노드가 동일 전역 {@code event_publication}을 보므로 인스턴스별
 * 동일값을 보고한다. Prometheus에서 합산(sum)이 아니라 {@code max}/{@code avg}로 봐야 한다
 * (예: {@code max(shop_events_publication_incomplete) by (application)}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "shop.events.metrics", name = "enabled", havingValue = "true")
public class IncompleteEventPublicationMetrics {

    /** 미완료 발행 건수 게이지 명 (Micrometer dot 표기 → prometheus는 shop_events_publication_incomplete). */
    static final String GAUGE_INCOMPLETE = "shop.events.publication.incomplete";

    private static final String COUNT_INCOMPLETE_SQL =
            "SELECT count(*) FROM event_publication WHERE completion_date IS NULL";

    private final JdbcTemplate jdbcTemplate;

    public IncompleteEventPublicationMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(GAUGE_INCOMPLETE, this, IncompleteEventPublicationMetrics::incompleteCount)
                .description("Number of incomplete (completion_date IS NULL) event publications in the outbox")
                .baseUnit("publications")
                .register(registry);
    }

    /** 스크레이프 시점 호출 — 미완료 건수. 쿼리 실패 시 -1(게이지가 앱을 죽이지 않게, §7). */
    double incompleteCount() {
        try {
            Long n = jdbcTemplate.queryForObject(COUNT_INCOMPLETE_SQL, Long.class);
            return n == null ? 0d : n.doubleValue();
        } catch (Exception e) {
            log.warn("[EventMetrics] incomplete publication count 조회 실패 — 게이지 -1 보고. reason={}",
                    e.getClass().getSimpleName());
            return -1d;
        }
    }
}
