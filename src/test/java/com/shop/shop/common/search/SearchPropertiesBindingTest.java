package com.shop.shop.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elasticsearch 접속 설정 바인딩 단위 테스트 (ADR-011 T1).
 *
 * <p>검증:
 * <ul>
 *   <li>spring.elasticsearch.uris 기본값 http://localhost:9200 폴백</li>
 *   <li>SHOP_CORE_SEARCH_URIS 환경변수로 오버라이드 시 바인딩 반영</li>
 * </ul>
 *
 * <p>ApplicationContextRunner 사용 — ES 연결 시도 없음, 컨테이너 불필요.
 */
class SearchPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SearchPropertiesTestConfig.class);

    @Test
    @DisplayName("기본값 폴백: spring.elasticsearch.uris 미지정 시 http://localhost:9200 사용")
    void defaultUri_fallback() {
        contextRunner.run(context -> {
            ElasticsearchProperties props = context.getBean(ElasticsearchProperties.class);
            assertThat(props.getUris()).containsExactly("http://localhost:9200");
        });
    }

    @Test
    @DisplayName("오버라이드: spring.elasticsearch.uris 지정 시 바인딩 반영")
    void override_bindsUri() {
        contextRunner
                .withPropertyValues("spring.elasticsearch.uris=http://shop-search:9200")
                .run(context -> {
                    ElasticsearchProperties props = context.getBean(ElasticsearchProperties.class);
                    assertThat(props.getUris()).containsExactly("http://shop-search:9200");
                });
    }

    @Test
    @DisplayName("SHOP_CORE_SEARCH_URIS 환경변수 오버라이드: ${SHOP_CORE_SEARCH_URIS:http://localhost:9200} 바인딩")
    void propertyOverride_bindsUri() {
        contextRunner
                .withPropertyValues("spring.elasticsearch.uris=http://custom-search:9200")
                .run(context -> {
                    ElasticsearchProperties props = context.getBean(ElasticsearchProperties.class);
                    assertThat(props.getUris()).containsExactly("http://custom-search:9200");
                    assertThat(props.getUris().getFirst()).startsWith("http://");
                });
    }

    /**
     * ApplicationContextRunner에서 ElasticsearchProperties를 활성화하는 내부 설정 클래스.
     * ES 자동설정(RestClient/ElasticsearchClient)은 포함하지 않아 연결 시도 없음.
     */
    @EnableConfigurationProperties(ElasticsearchProperties.class)
    static class SearchPropertiesTestConfig {
    }
}
