package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Elasticsearch 상품 색인 빈 자동설정.
 *
 * <p><b>왜 @AutoConfiguration인가:</b><br>
 * {@code @ConditionalOnBean}을 {@code @Service}·{@code @Component} 또는
 * 일반 {@code @Configuration} 클래스에 직접 붙이면 컴포넌트 스캔 단계(부트스트랩 초기)에서
 * 조건이 평가된다. 이 시점에는 Spring Boot 자동설정(autoconfiguration) 단계가 아직 실행되지
 * 않으므로 {@code ElasticsearchClient} 빈이 컨텍스트에 존재하지 않아 조건이 항상 false로
 * 평가된다.
 *
 * <p>{@code @AutoConfiguration}은 {@code META-INF/spring/…AutoConfiguration.imports}에 등록되어
 * 자동설정 단계(user 빈 이후, Spring Boot 자동설정 순서)에서 로드된다. 이 단계에서는
 * {@link ElasticsearchClientAutoConfiguration}이 이미 실행된 뒤이므로 {@code ElasticsearchClient}
 * 빈이 존재해 {@code @ConditionalOnBean}이 올바르게 평가된다.
 *
 * <p>이 클래스가 등록하는 빈:
 * <ul>
 *   <li>{@link ProductSearchIndexAdmin} — ES 저수준 인덱스/alias/bulk 관리 (T4 공용)</li>
 *   <li>{@link ProductSearchIndexService} — ES upsert 서비스 (T2+3 증분 indexer)</li>
 *   <li>{@link ProductSearchIndexBootstrap} — 기동 시 인덱스·alias 멱등 생성</li>
 * </ul>
 *
 * <p>{@code ElasticsearchClient}가 없는 테스트 컨텍스트(ES 자동설정 제외 시)에서는
 * 이 자동설정이 빈을 등록하지 않아 풀 {@code @SpringBootTest} 컨텍스트 로드
 * 회귀를 구조적으로 차단한다(verification-gate §4).
 *
 * <p>{@code shop.search.indexer.enabled=true}일 때만 활성화된다. ES 자동설정이 포함된 PG 전용
 * 통합 테스트에서 ES 연결 시도로 인한 컨텍스트 로드 실패를 추가로 차단한다.
 */
@AutoConfiguration(after = ElasticsearchClientAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.search.indexer.enabled", havingValue = "true")
@ConditionalOnBean(ElasticsearchClient.class)
public class ProductSearchIndexConfig {

    @Bean
    public ProductSearchIndexAdmin productSearchIndexAdmin(
            ElasticsearchClient elasticsearchClient,
            ObjectMapper objectMapper) {
        return new ProductSearchIndexAdmin(elasticsearchClient, objectMapper);
    }

    @Bean
    public ProductSearchIndexService productSearchIndexService(ElasticsearchClient elasticsearchClient) {
        return new ProductSearchIndexService(elasticsearchClient);
    }

    @Bean
    public ProductSearchIndexBootstrap productSearchIndexBootstrap(
            ProductSearchIndexAdmin productSearchIndexAdmin) {
        return new ProductSearchIndexBootstrap(productSearchIndexAdmin);
    }

    @Bean
    public EsProductSearchAdapter esProductSearchAdapter(
            ElasticsearchClient elasticsearchClient,
            MeterRegistry meterRegistry,
            @Value("${shop.search.query.timeout-ms:800}") long timeoutMs,
            @Value("${shop.search.query.cooldown-ms:5000}") long cooldownMs) {
        return new EsProductSearchAdapter(elasticsearchClient, meterRegistry, timeoutMs, cooldownMs);
    }
}
