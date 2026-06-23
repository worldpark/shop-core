package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.PutAliasResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

/**
 * Elasticsearch 버전드 상품 인덱스 + alias 멱등 부트스트랩.
 *
 * <p>애플리케이션 기동 시 {@code products-v1} 인덱스와 {@code products} alias가 없을 때만 생성한다.
 * 이미 존재하면 무동작(멱등). ES 미가용이어도 부팅을 막지 않는다(try/catch + WARN 로깅).
 *
 * <p><b>빈 등록 방식</b>: {@link ProductSearchIndexConfig}에서
 * {@code @ConditionalOnBean(ElasticsearchClient.class)}와 함께 {@code @Bean}으로 등록된다.
 * {@code @Component}를 직접 붙이면 컴포넌트 스캔 단계(자동설정 이전)에서 조건 평가가 이루어져
 * 빈 생성이 스킵되는 문제가 있어 {@code @Configuration} 레벨 등록으로 분리하였다.
 *
 * <p><b>T4(060) 재사용</b>: {@link #ensureIndex()} 메서드를 {@code public}으로 노출하여
 * T4 전량 재색인 잡이 "새 버전 인덱스 생성 → 백필 → alias 스왑" 시 재사용할 수 있다.
 *
 * <p>매핑 JSON 단일 출처: {@code search/product-index.json} (classpath 리소스).
 */
@Slf4j
@RequiredArgsConstructor
public class ProductSearchIndexBootstrap implements ApplicationRunner {

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex();
    }

    /**
     * {@code products-v1} 인덱스 + {@code products} alias를 멱등 생성한다.
     *
     * <p>ES 미가용이어도 예외를 삼키고 WARN 로깅 후 계속 진행한다(ADR-011: 검색 장애가 핵심 경로 비차단).
     * T4(060) 전량 재색인이 이 메서드를 재사용할 수 있다.
     */
    public void ensureIndex() {
        try {
            doEnsureIndex();
        } catch (Exception e) {
            log.warn("[SearchIndex] Bootstrap failed (ES may be unavailable). Boot continues. error={}", e.getMessage());
        }
    }

    private void doEnsureIndex() throws Exception {
        String indexName = ProductSearchIndexNames.CURRENT_INDEX;
        String aliasName = ProductSearchIndexNames.ALIAS;

        boolean indexExists = elasticsearchClient.indices()
                .exists(req -> req.index(indexName))
                .value();

        if (indexExists) {
            log.info("[SearchIndex] Index '{}' already exists. Bootstrap skipped (idempotent).", indexName);
            ensureAlias(indexName, aliasName);
            return;
        }

        log.info("[SearchIndex] Creating index '{}' with Nori mapping...", indexName);
        createIndex(indexName);
        ensureAlias(indexName, aliasName);
        log.info("[SearchIndex] Index '{}' + alias '{}' created successfully.", indexName, aliasName);
    }

    @SuppressWarnings("unchecked")
    private void createIndex(String indexName) throws Exception {
        ClassPathResource resource = new ClassPathResource(ProductSearchIndexNames.MAPPING_RESOURCE);

        try (InputStream is = resource.getInputStream()) {
            Map<String, Object> indexConfig = objectMapper.readValue(is, Map.class);

            String settingsJson = objectMapper.writeValueAsString(indexConfig.get("settings"));
            String mappingsJson = objectMapper.writeValueAsString(indexConfig.get("mappings"));

            CreateIndexResponse response = elasticsearchClient.indices().create(req -> req
                    .index(indexName)
                    .withJson(buildIndexJson(settingsJson, mappingsJson))
            );

            if (!Boolean.TRUE.equals(response.acknowledged())) {
                log.warn("[SearchIndex] Create index '{}' was not acknowledged by ES.", indexName);
            }
        }
    }

    private void ensureAlias(String indexName, String aliasName) throws Exception {
        boolean aliasExists = elasticsearchClient.indices()
                .existsAlias(req -> req.name(aliasName))
                .value();

        if (aliasExists) {
            log.debug("[SearchIndex] Alias '{}' already exists.", aliasName);
            return;
        }

        PutAliasResponse aliasResponse = elasticsearchClient.indices()
                .putAlias(req -> req.index(indexName).name(aliasName));

        if (!Boolean.TRUE.equals(aliasResponse.acknowledged())) {
            log.warn("[SearchIndex] Put alias '{}' → '{}' was not acknowledged.", aliasName, indexName);
        } else {
            log.info("[SearchIndex] Alias '{}' → '{}' created.", aliasName, indexName);
        }
    }

    /**
     * settings + mappings를 하나의 JSON 스트림으로 결합한다.
     */
    private InputStream buildIndexJson(String settingsJson, String mappingsJson) {
        String combined = "{\"settings\":" + settingsJson + ",\"mappings\":" + mappingsJson + "}";
        return new java.io.ByteArrayInputStream(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
