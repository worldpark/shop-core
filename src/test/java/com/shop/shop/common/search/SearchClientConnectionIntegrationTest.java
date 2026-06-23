package com.shop.shop.common.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elasticsearch 연결 + actuator health 와이어링 통합 테스트 (ADR-011 T1).
 *
 * <p>패키지: {@code common/search} — ES = 횡단 공통 인프라(package-structure-rule).
 * {@code PasswordResetRedisIntegrationTest}의 Testcontainers 메커니즘을 계승:
 * <ul>
 *   <li>{@code @ServiceConnection PostgreSQLContainer} — PG 연결 자동 주입</li>
 *   <li>{@code @DynamicPropertySource} — ES 매핑 포트를 {@code spring.elasticsearch.uris}로 주입</li>
 *   <li>{@code @TestPropertySource} — test {@code application.yml}의 {@code spring.autoconfigure.exclude}를
 *       단일 키로 덮어써 ES 자동설정(ElasticsearchClientAutoConfiguration,
 *       ElasticsearchRestClientAutoConfiguration)을 재활성화(Kafka만 제외, ES는 포함)</li>
 * </ul>
 *
 * <p>검증 게이트 3: {@code /actuator/health} 응답에서
 * {@code components.elasticsearch.status == "UP"} 실측 단언.
 * 검증 게이트 5: ES 컨테이너가 정상 기동 후 actuator health 조회까지 부팅 성공
 * (eager 연결이 있더라도 ES가 준비된 후 조회이므로 통과, ES 없이 컨텍스트 로드 보장은
 * test application.yml exclude로 별도 보장).
 * 검증 게이트 결정 5: readiness 그룹에 ES 미포함(readinessState만) 단언.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        // test application.yml의 spring.autoconfigure.exclude를 단일 키로 덮어써 ES 자동설정 재활성.
        // 이 키는 목록이 아닌 단일 값이므로 덮어쓰면 test yml 전체 목록이 교체됨(누적 아님).
        // ES 자동설정(ElasticsearchClientAutoConfiguration, ElasticsearchRestClientAutoConfiguration)을
        // 포함시키고 Kafka·Flyway 등 필요한 나머지 exclude만 유지한다.
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "management.endpoints.web.exposure.include=health",
        "management.endpoint.health.show-details=always",
        "management.endpoint.health.probes.enabled=true"
})
class SearchClientConnectionIntegrationTest {

    /** PG — @ServiceConnection 자동 url/username/password 주입 (PasswordResetRedisIntegrationTest 계승). */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /**
     * ES — 공식 이미지 + xpack.security.enabled=false(보안 완화 단일 노드).
     * 버전은 compose의 ES_IMAGE_TAG 기본값(8.15.3)과 동일.
     * analysis-nori 설치 여부는 본 Task 범위(연결+health)에서 단언하지 않음 — T2+3에서 매핑 시 검증.
     */
    @Container
    @SuppressWarnings("resource")
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
                    .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        // ES 매핑 포트를 spring.elasticsearch.uris로 주입 (DynamicPropertySource 계승)
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    // ============================================================
    // 게이트 3: /actuator/health — elasticsearch 컴포넌트 UP
    // ============================================================

    @Test
    @DisplayName("게이트 3: /actuator/health components.elasticsearch.status == UP")
    @SuppressWarnings("unchecked")
    void actuatorHealth_elasticsearchComponentUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        // components.elasticsearch 존재 확인
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).as("components 존재").isNotNull();
        assertThat(components).as("elasticsearch health 컴포넌트 존재").containsKey("elasticsearch");

        // elasticsearch.status == UP (게이트 3 핵심 단언)
        Map<String, Object> elasticsearchHealth = (Map<String, Object>) components.get("elasticsearch");
        assertThat(elasticsearchHealth.get("status"))
                .as("elasticsearch health status UP")
                .isEqualTo("UP");
    }

    // ============================================================
    // 게이트 결정 5: /actuator/health/readiness — ES 컴포넌트 미포함 단언
    // readiness 그룹에 readinessState만 포함(결정 5) → ES 다운이 readiness DOWN 유발 안 함.
    // readiness 응답 body를 String으로 먼저 수신해 elasticsearch 키 미포함 확인.
    // ============================================================

    @Test
    @DisplayName("게이트 결정 5: /actuator/health/readiness — elasticsearch 키 미포함(readinessState만 포함)")
    void actuatorReadiness_elasticsearchComponentAbsent() {
        // String으로 수신. HTTP status 무관(200/503)하게 elasticsearch 키 미포함 단언.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/readiness", String.class);

        String body = response.getBody();
        // 1) readiness 엔드포인트가 실제로 JSON 응답을 렌더했음을 먼저 단언 — "응답이 비어서 통과"를 방지.
        //    readiness 그룹이 존재하면 최소 {"status":"UP"} 형태의 non-blank JSON이 반환된다.
        assertThat(body)
                .as("readiness 엔드포인트가 non-blank JSON을 응답해야 한다(readiness 그룹 존재 전제)")
                .isNotBlank();
        assertThat(body)
                .as("readiness 응답에 status 키 포함 — readiness 그룹이 실제로 응답했음을 증명")
                .contains("status");
        // 2) readiness 그룹에 elasticsearch 컴포넌트가 없어야 한다(결정 5 핵심 단언).
        //    application.yml: management.endpoint.health.group.readiness.include=readinessState 설정으로
        //    ES 컴포넌트는 readiness 그룹에서 제외 → ES 다운이 readiness DOWN을 유발하지 않음.
        assertThat(body)
                .as("readiness 응답에 elasticsearch 미포함 — ES 다운이 readiness DOWN 유발 안 함(결정 5)")
                .doesNotContain("\"elasticsearch\"");
    }

    // ============================================================
    // 전체 health 상태 확인 (UP 단언)
    // ============================================================

    @Test
    @DisplayName("전체 /actuator/health status UP")
    @SuppressWarnings("unchecked")
    void actuatorHealth_statusUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).as("전체 health status").isEqualTo("UP");
    }
}
