package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Elasticsearch 상품 색인 저수준 관리 연산 공용 어댑터.
 *
 * <p>T4(060) 풀 재색인 잡과 {@link ProductSearchIndexBootstrap}이 공유하는 ES 인덱스/alias 조작
 * 로직을 한 곳에 모은다. ES 연결/클라이언트 초기화는 T1이 담당 — 이 클래스는 주어진 클라이언트를
 * 단순 위임 방식으로 사용한다.
 *
 * <p><b>빈 등록</b>: {@link ProductSearchIndexConfig}에서 {@code @ConditionalOnBean(ElasticsearchClient)}
 * + {@code @ConditionalOnProperty(shop.search.indexer.enabled)} 게이트 아래 {@code @Bean}으로 등록된다.
 *
 * <p>매핑 JSON 단일 출처: {@link ProductSearchIndexNames#MAPPING_RESOURCE} (classpath 리소스).
 * 동일 JSON을 적재해 신규 버전 인덱스를 생성한다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProductSearchIndexAdmin {

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // 인덱스 생성
    // ========================================================================

    /**
     * Nori 매핑으로 지정된 이름의 인덱스를 생성한다.
     *
     * <p>매핑 JSON은 {@link ProductSearchIndexNames#MAPPING_RESOURCE}에서 로드한다.
     * 인덱스가 이미 존재하면 ES가 오류를 반환한다(호출자가 존재 여부를 먼저 확인해야 한다).
     *
     * @param indexName 생성할 인덱스 이름 (예: {@code products-v1}, {@code products-v1719000000000})
     * @throws IOException ES 통신 오류 또는 클래스패스 리소스 읽기 실패
     */
    @SuppressWarnings("unchecked")
    public void createIndex(String indexName) throws IOException {
        ClassPathResource resource = new ClassPathResource(ProductSearchIndexNames.MAPPING_RESOURCE);

        try (InputStream is = resource.getInputStream()) {
            Map<String, Object> indexConfig = objectMapper.readValue(is, Map.class);

            String settingsJson = objectMapper.writeValueAsString(indexConfig.get("settings"));
            String mappingsJson = objectMapper.writeValueAsString(indexConfig.get("mappings"));

            InputStream combinedStream = buildIndexJson(settingsJson, mappingsJson);

            CreateIndexResponse response = elasticsearchClient.indices()
                    .create(req -> req
                            .index(indexName)
                            .withJson(combinedStream)
                    );

            if (!Boolean.TRUE.equals(response.acknowledged())) {
                log.warn("[SearchIndexAdmin] createIndex '{}' was not acknowledged by ES.", indexName);
            } else {
                log.info("[SearchIndexAdmin] Index '{}' created.", indexName);
            }
        }
    }

    // ========================================================================
    // alias 조작
    // ========================================================================

    /**
     * {@link ProductSearchIndexNames#ALIAS} alias가 어느 인덱스에든 존재하는지 확인한다.
     *
     * @return alias가 존재하면 {@code true}
     * @throws IOException ES 통신 오류
     */
    public boolean aliasExists() throws IOException {
        return elasticsearchClient.indices()
                .existsAlias(req -> req.name(ProductSearchIndexNames.ALIAS))
                .value();
    }

    /**
     * {@link ProductSearchIndexNames#ALIAS} alias가 현재 가리키는 인덱스 이름 집합을 반환한다.
     *
     * @return alias가 연결된 인덱스 이름 집합 (alias 없으면 빈 집합)
     * @throws IOException ES 통신 오류
     */
    public Set<String> indicesBehindAlias() throws IOException {
        if (!aliasExists()) {
            return Set.of();
        }
        return elasticsearchClient.indices()
                .getAlias(req -> req.name(ProductSearchIndexNames.ALIAS))
                .result()
                .keySet();
    }

    /**
     * {@link ProductSearchIndexNames#ALIAS} alias를 {@code newIndex}로 원자적으로 전환한다.
     *
     * <p>단일 {@code updateAliases} 호출에 "기존 인덱스에서 alias 제거" + "신규 인덱스에 alias 추가"
     * 액션을 함께 실어 무중단 전환한다. 기존 alias가 없으면 추가만 수행한다.
     *
     * @param newIndex alias가 가리킬 새 인덱스 이름
     * @throws IOException ES 통신 오류
     */
    public void pointAliasTo(String newIndex) throws IOException {
        Set<String> currentIndices = indicesBehindAlias();
        String aliasName = ProductSearchIndexNames.ALIAS;

        elasticsearchClient.indices().updateAliases(req -> {
            // 새 인덱스에 alias 추가
            req.actions(a -> a.add(add -> add.index(newIndex).alias(aliasName)));
            // 기존 인덱스에서 alias 제거 (원자적 단일 요청으로 처리됨)
            for (String oldIndex : currentIndices) {
                if (!oldIndex.equals(newIndex)) {
                    req.actions(a -> a.remove(remove -> remove.index(oldIndex).alias(aliasName)));
                }
            }
            return req;
        });

        log.info("[SearchIndexAdmin] Alias '{}' now points to '{}'.", aliasName, newIndex);
    }

    // ========================================================================
    // bulk 색인
    // ========================================================================

    /**
     * 문서 목록을 지정 인덱스에 bulk 색인한다.
     *
     * <p>{@code _id=productId}, plain index(external version 없음 — 새 인덱스 단일 패스라
     * 순서·덮어쓰기 이슈 없음). 배치 내 부분 실패({@code response.errors()==true})가 있으면
     * 실패 문서 정보를 로깅한 후 예외를 던져 잡 전체를 중단시킨다(alias swap 미수행 보장).
     *
     * @param indexName 대상 인덱스 이름
     * @param docs      색인할 문서 목록
     * @throws IOException              ES 통신 오류
     * @throws IllegalStateException    bulk 부분 실패 발생 시
     */
    public void bulkIndex(String indexName, List<ProductSearchDocument> docs) throws IOException {
        if (docs.isEmpty()) {
            return;
        }

        List<BulkOperation> operations = new ArrayList<>(docs.size());
        for (ProductSearchDocument doc : docs) {
            final ProductSearchDocument finalDoc = doc;
            operations.add(BulkOperation.of(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(String.valueOf(finalDoc.productId()))
                            .document(finalDoc)
                    )
            ));
        }

        BulkRequest bulkRequest = BulkRequest.of(req -> req.operations(operations));
        BulkResponse response = elasticsearchClient.bulk(bulkRequest);

        if (response.errors()) {
            List<String> failures = new ArrayList<>();
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    failures.add(String.format("id=%s error=%s reason=%s",
                            item.id(),
                            item.error().type(),
                            item.error().reason()));
                }
            }
            String failureMsg = String.join("; ", failures);
            log.error("[SearchIndexAdmin] bulk partial failure in index '{}': {}", indexName, failureMsg);
            throw new IllegalStateException(
                    "ES bulk failed for index '" + indexName + "': " + failureMsg);
        }

        log.debug("[SearchIndexAdmin] bulk indexed {} docs into '{}'.", docs.size(), indexName);
    }

    // ========================================================================
    // 인덱스 삭제 · 목록
    // ========================================================================

    /**
     * 지정 인덱스를 삭제한다.
     *
     * @param indexName 삭제할 인덱스 이름
     * @throws IOException ES 통신 오류
     */
    public void deleteIndex(String indexName) throws IOException {
        elasticsearchClient.indices().delete(req -> req.index(indexName));
        log.info("[SearchIndexAdmin] Index '{}' deleted.", indexName);
    }

    /**
     * {@code products-v*} 패턴에 매칭되는 인덱스 이름 목록을 반환한다.
     *
     * <p>T4 정리 정책(현재 + 직전 1개 보존)에서 삭제 대상을 선별하는 데 사용한다.
     * creation_date 정렬이 필요한 경우 ES indices.get() 응답의 settings.creation_date를 활용한다.
     *
     * @return {@code products-v*} 패턴 인덱스 이름 목록
     * @throws IOException ES 통신 오류
     */
    public List<String> listVersionIndices() throws IOException {
        Map<String, IndexState> indices;
        try {
            indices = elasticsearchClient.indices()
                    .get(req -> req.index("products-v*"))
                    .result();
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            // index_not_found_exception — products-v* 패턴에 매칭되는 인덱스 없음
            if ("index_not_found_exception".equals(e.error() != null ? e.error().type() : null)) {
                return List.of();
            }
            throw e;
        }
        return new ArrayList<>(indices.keySet());
    }

    /**
     * {@code products-v*} 패턴 인덱스 목록을 creation_date 내림차순으로 반환한다.
     *
     * <p>T4 정리 정책에서 오래된 인덱스를 식별하는 데 사용한다.
     * creation_date는 ES 내부 설정값({@code settings.index.creation_date})에서 조회한다.
     *
     * @return (indexName, creationDateMillis) 쌍 목록, creation_date 내림차순
     * @throws IOException ES 통신 오류
     */
    public List<Map.Entry<String, Long>> listVersionIndicesWithCreationDate() throws IOException {
        Map<String, IndexState> indices;
        try {
            indices = elasticsearchClient.indices()
                    .get(req -> req.index("products-v*"))
                    .result();
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if ("index_not_found_exception".equals(e.error() != null ? e.error().type() : null)) {
                return List.of();
            }
            throw e;
        }

        return indices.entrySet().stream()
                .map(entry -> {
                    long creationDate = 0L;
                    try {
                        IndexState state = entry.getValue();
                        if (state.settings() != null
                                && state.settings().index() != null
                                && state.settings().index().creationDate() != null) {
                            creationDate = state.settings().index().creationDate();
                        }
                    } catch (Exception ignored) {
                        // creation_date 조회 실패 시 0으로 처리
                    }
                    return Map.entry(entry.getKey(), creationDate);
                })
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
    }

    // ========================================================================
    // 헬퍼
    // ========================================================================

    /**
     * settings + mappings를 하나의 JSON 스트림으로 결합한다.
     */
    private InputStream buildIndexJson(String settingsJson, String mappingsJson) {
        String combined = "{\"settings\":" + settingsJson + ",\"mappings\":" + mappingsJson + "}";
        return new java.io.ByteArrayInputStream(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
