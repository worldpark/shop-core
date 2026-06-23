package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.service.PublicProductSort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EsProductSearchAdapter 단위 테스트 — DSL 조립, 쿨다운, 폴백 신호 검증.
 *
 * <p>ElasticsearchClient는 Mockito mock. 실제 ES 없이 어댑터 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class EsProductSearchAdapterTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    private SimpleMeterRegistry meterRegistry;
    private EsProductSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        adapter = new EsProductSearchAdapter(elasticsearchClient, meterRegistry, 800L, 5000L);
    }

    // =============================================================
    // 성공 경로 — 검색 결과 반환
    // =============================================================

    @Test
    @DisplayName("search — ES 성공 시 ids 랭킹 순서 + totalHits 반환")
    void search_esSuccess_returnsHits() throws Exception {
        doReturn(buildMockResponse(List.of("10", "20", "5"), 100L))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        Optional<ProductSearchHits> result = adapter.search(
                "노트북", null, List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT),
                PublicProductSort.LATEST, 0, 20);

        assertThat(result).isPresent();
        assertThat(result.get().ids()).containsExactly(10L, 20L, 5L);
        assertThat(result.get().totalHits()).isEqualTo(100L);
    }

    @Test
    @DisplayName("search — ES 빈 결과도 Optional.of(empty ids) 반환 (totalHits 0)")
    void search_emptyResult_returnsEmptyHits() throws Exception {
        doReturn(buildMockResponse(List.of(), 0L))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        Optional<ProductSearchHits> result = adapter.search(
                "존재안함", null, List.of(ProductStatus.ON_SALE), PublicProductSort.PRICE_ASC, 0, 10);

        assertThat(result).isPresent();
        assertThat(result.get().ids()).isEmpty();
        assertThat(result.get().totalHits()).isEqualTo(0L);
    }

    // =============================================================
    // 폴백 신호 — ES 예외 시 빈 Optional 반환
    // =============================================================

    @Test
    @DisplayName("search — ES IOException 시 빈 Optional 반환 (폴백 신호, 예외 누수 없음)")
    void search_esIoException_returnsEmpty() throws Exception {
        doThrow(new IOException("ES connection refused"))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        Optional<ProductSearchHits> result = adapter.search(
                "검색어", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 20);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("search — ES RuntimeException 시 빈 Optional 반환 (도메인 예외 비누수)")
    void search_esRuntimeException_returnsEmpty() throws Exception {
        doThrow(new RuntimeException("ES timeout"))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        Optional<ProductSearchHits> result = adapter.search(
                "검색어", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 20);

        assertThat(result).isEmpty();
    }

    // =============================================================
    // 쿨다운 — 실패 후 쿨다운 윈도우 내 ES 스킵
    // =============================================================

    @Test
    @DisplayName("cooldown — ES 실패 후 쿨다운 활성화, 다음 요청은 ES 스킵하고 빈 Optional 반환")
    void cooldown_afterFailure_skipsEsWithinWindow() throws Exception {
        doThrow(new IOException("ES down"))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        // 첫 번째 호출 → 실패 → 쿨다운 활성
        adapter.search("키워드", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 10);

        // 쿨다운이 활성화되었는지 확인
        assertThat(adapter.getCooldownUntilEpochMs()).isGreaterThan(System.currentTimeMillis());

        // 두 번째 호출 → 쿨다운 중이므로 ES 스킵
        Optional<ProductSearchHits> secondResult = adapter.search(
                "키워드", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 10);

        assertThat(secondResult).isEmpty();
    }

    @Test
    @DisplayName("cooldown — 쿨다운 만료 후 ES 재시도")
    void cooldown_afterExpiry_retriesEs() throws Exception {
        doReturn(buildMockResponse(List.of("1"), 1L))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        // 쿨다운을 과거 시각으로 직접 설정 (만료됨)
        adapter.setCooldownUntilEpochMs(System.currentTimeMillis() - 1000L);

        Optional<ProductSearchHits> result = adapter.search(
                "키워드", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 10);

        assertThat(result).isPresent();
        assertThat(result.get().ids()).containsExactly(1L);
    }

    // =============================================================
    // from/size 계산
    // =============================================================

    @Test
    @DisplayName("search — page=2, size=10 → from=20 (페이지 오프셋)")
    void search_pageAndSize_correctFromOffset() throws Exception {
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        doReturn(buildMockResponse(List.of(), 0L))
                .when(elasticsearchClient).search(requestCaptor.capture(), any());

        adapter.search("keyword", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 2, 10);

        SearchRequest captured = requestCaptor.getValue();
        assertThat(captured.from()).isEqualTo(20);
        assertThat(captured.size()).isEqualTo(10);
    }

    // =============================================================
    // categoryId term 필터
    // =============================================================

    @Test
    @DisplayName("search — categoryId != null 시 요청이 만들어진다")
    void search_withCategoryId_requestBuilt() throws Exception {
        doReturn(buildMockResponse(List.of(), 0L))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        Optional<ProductSearchHits> result = adapter.search(
                "노트북", 5L, List.of(ProductStatus.ON_SALE), PublicProductSort.PRICE_ASC, 0, 10);

        // categoryId 필터 포함 요청이 ES에 전달되고 정상 반환
        assertThat(result).isPresent();
    }

    // =============================================================
    // 메트릭 — Timer 기록
    // =============================================================

    @Test
    @DisplayName("search — ES 성공 시 product.search.es.duration Timer 기록")
    void search_success_recordsTimer() throws Exception {
        doReturn(buildMockResponse(List.of(), 0L))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        adapter.search("keyword", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 10);

        assertThat(meterRegistry.find("product.search.es.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("product.search.es.duration").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("search — ES 실패 시에도 product.search.es.duration Timer 기록")
    void search_failure_recordsTimer() throws Exception {
        doThrow(new IOException("ES down"))
                .when(elasticsearchClient).search(any(SearchRequest.class), any());

        adapter.search("keyword", null, List.of(ProductStatus.ON_SALE), PublicProductSort.LATEST, 0, 10);

        assertThat(meterRegistry.find("product.search.es.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("product.search.es.duration").timer().count()).isEqualTo(1);
    }

    // =============================================================
    // 헬퍼 — SearchResponse mock (raw type으로 ES 제네릭 복잡성 우회)
    // =============================================================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SearchResponse<Void> buildMockResponse(List<String> ids, long total) {
        SearchResponse<Void> mockResponse = mock(SearchResponse.class);

        // hits().hits() 반환값
        List<Hit<Void>> hitList = ids.stream()
                .map(id -> {
                    Hit<Void> hit = mock(Hit.class);
                    when(hit.id()).thenReturn(id);
                    return hit;
                })
                .toList();

        // hits().total()
        TotalHits totalHits = mock(TotalHits.class);
        when(totalHits.value()).thenReturn(total);

        // HitsMetadata mock
        HitsMetadata<Void> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(hitList);
        when(hitsMetadata.total()).thenReturn(totalHits);

        when(mockResponse.hits()).thenReturn(hitsMetadata);
        return mockResponse;
    }
}
