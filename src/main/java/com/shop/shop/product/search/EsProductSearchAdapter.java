package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.service.PublicProductSort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Elasticsearch 기반 상품 검색 어댑터.
 *
 * <p>bool 쿼리: must(multi_match name^3+description^2+categoryName^1) + filter(status terms + categoryId term).
 * 정렬: LATEST → _score desc + productId desc, PRICE_ASC → displayPrice asc + productId asc,
 * PRICE_DESC → displayPrice desc + productId desc.
 *
 * <p><b>쿨다운(인프로세스 미니 서킷)</b>: ES 호출 실패 시 {@code cooldownUntilEpochMs}를 설정해
 * 쿨다운 윈도우 내 요청은 ES를 스킵하고 즉시 빈 Optional을 반환한다(매 요청 타임아웃 비용 회피).
 *
 * <p><b>예외 격리</b>: 어댑터 경계에서 모든 예외를 흡수하고 빈 Optional을 반환한다.
 * 도메인 예외가 이 어댑터 밖으로 누수되지 않는다(검색 장애가 핵심 경로를 차단하지 않음).
 *
 * <p>ES status term 값 = {@code ProductStatus.name()}(String keyword — 인덱스 매핑 일치).
 *
 * <p><b>빈 등록</b>: {@link ProductSearchIndexConfig}에서 {@code @ConditionalOnBean(ElasticsearchClient)}
 * + {@code @ConditionalOnProperty(shop.search.indexer.enabled)} 게이트 아래 등록된다.
 */
@Slf4j
public class EsProductSearchAdapter implements ProductSearchPort {

    private static final String TIMER_ES_DURATION = "product.search.es.duration";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_CATEGORY_NAME = "categoryName";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_CATEGORY_ID = "categoryId";
    private static final String FIELD_DISPLAY_PRICE = "displayPrice";
    private static final String FIELD_PRODUCT_ID = "productId";

    private final ElasticsearchClient elasticsearchClient;
    private final MeterRegistry meterRegistry;
    private final long timeoutMs;
    private final long cooldownMs;

    /** 쿨다운 만료 시각 (epoch ms). 0 = 초기/비활성. */
    private final AtomicLong cooldownUntilEpochMs = new AtomicLong(0L);

    public EsProductSearchAdapter(
            ElasticsearchClient elasticsearchClient,
            MeterRegistry meterRegistry,
            @Value("${shop.search.query.timeout-ms:800}") long timeoutMs,
            @Value("${shop.search.query.cooldown-ms:5000}") long cooldownMs) {
        this.elasticsearchClient = elasticsearchClient;
        this.meterRegistry = meterRegistry;
        this.timeoutMs = timeoutMs;
        this.cooldownMs = cooldownMs;
    }

    @Override
    public Optional<ProductSearchHits> search(
            String keyword,
            Long categoryId,
            List<ProductStatus> statuses,
            PublicProductSort sort,
            int page,
            int size) {

        // 쿨다운 중이면 ES 스킵
        long now = System.currentTimeMillis();
        if (now < cooldownUntilEpochMs.get()) {
            log.debug("[ProductSearch] ES cooldown active — skipping ES, page={}, size={}", page, size);
            return Optional.empty();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SearchResponse<Void> response = executeSearch(keyword, categoryId, statuses, sort, page, size);

            List<Long> ids = response.hits().hits().stream()
                    .map(Hit::id)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());

            long totalHits = response.hits().total() != null
                    ? response.hits().total().value()
                    : (long) ids.size();

            return Optional.of(new ProductSearchHits(ids, totalHits));

        } catch (Exception e) {
            // 어댑터 경계에서 모든 예외 흡수 — 도메인 누수 금지
            activateCooldown();
            log.warn("[ProductSearch] ES search failed (reason={}, ids-count=0) — falling back to PG",
                    e.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            sample.stop(Timer.builder(TIMER_ES_DURATION)
                    .description("ES product search latency")
                    .register(meterRegistry));
        }
    }

    private SearchResponse<Void> executeSearch(
            String keyword,
            Long categoryId,
            List<ProductStatus> statuses,
            PublicProductSort sort,
            int page,
            int size) throws Exception {

        // status terms 필터 값 = enum name 문자열 (인덱스 keyword 매핑 일치)
        List<String> statusNames = statuses.stream()
                .map(Enum::name)
                .collect(Collectors.toList());

        // multi_match 필드^부스팅
        List<String> fields = List.of(
                FIELD_NAME + "^3",
                FIELD_DESCRIPTION + "^2",
                FIELD_CATEGORY_NAME + "^1"
        );

        // bool query 조립
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // must: multi_match (Nori 형태소 분석)
        boolBuilder.must(Query.of(q -> q
                .multiMatch(mm -> mm
                        .query(keyword)
                        .fields(fields)
                        .type(TextQueryType.BestFields)
                )
        ));

        // filter: status terms
        boolBuilder.filter(Query.of(q -> q
                .terms(t -> t
                        .field(FIELD_STATUS)
                        .terms(tv -> tv.value(
                                statusNames.stream()
                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                        .collect(Collectors.toList())
                        ))
                )
        ));

        // filter: categoryId term (optional)
        if (categoryId != null) {
            final Long catId = categoryId;
            boolBuilder.filter(Query.of(q -> q
                    .term(t -> t
                            .field(FIELD_CATEGORY_ID)
                            .value(catId)
                    )
            ));
        }

        // sort 설정
        List<SortOptions> sortOptions = buildSortOptions(sort);

        int from = page * size;

        SearchRequest request = SearchRequest.of(r -> r
                .index(ProductSearchIndexNames.ALIAS)
                .query(Query.of(q -> q.bool(boolBuilder.build())))
                .sort(sortOptions)
                .from(from)
                .size(size)
                .source(s -> s.fetch(false))   // _source 불필요 — ID만 추출
                .timeout(timeoutMs + "ms")
        );

        return elasticsearchClient.search(request, Void.class);
    }

    /**
     * 정렬 기준에 따라 ES SortOptions 목록을 생성한다.
     *
     * <p>LATEST: _score desc + productId desc (tiebreak)<br>
     * PRICE_ASC: displayPrice asc + productId asc<br>
     * PRICE_DESC: displayPrice desc + productId desc
     */
    private List<SortOptions> buildSortOptions(PublicProductSort sort) {
        List<SortOptions> options = new ArrayList<>();
        switch (sort) {
            case PRICE_ASC -> {
                options.add(SortOptions.of(s -> s.field(f -> f.field(FIELD_DISPLAY_PRICE).order(SortOrder.Asc))));
                options.add(SortOptions.of(s -> s.field(f -> f.field(FIELD_PRODUCT_ID).order(SortOrder.Asc))));
            }
            case PRICE_DESC -> {
                options.add(SortOptions.of(s -> s.field(f -> f.field(FIELD_DISPLAY_PRICE).order(SortOrder.Desc))));
                options.add(SortOptions.of(s -> s.field(f -> f.field(FIELD_PRODUCT_ID).order(SortOrder.Desc))));
            }
            default -> {
                // LATEST → _score desc (연관도 우선) + productId desc tiebreak
                options.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
                options.add(SortOptions.of(s -> s.field(f -> f.field(FIELD_PRODUCT_ID).order(SortOrder.Desc))));
            }
        }
        return options;
    }

    private void activateCooldown() {
        cooldownUntilEpochMs.set(System.currentTimeMillis() + cooldownMs);
    }

    /**
     * 쿨다운 만료 시각(테스트 접근용).
     */
    long getCooldownUntilEpochMs() {
        return cooldownUntilEpochMs.get();
    }

    /**
     * 쿨다운 강제 설정(테스트 접근용).
     */
    void setCooldownUntilEpochMs(long epochMs) {
        cooldownUntilEpochMs.set(epochMs);
    }
}
