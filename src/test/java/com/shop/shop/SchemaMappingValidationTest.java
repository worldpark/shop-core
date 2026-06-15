package com.shop.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전역 스키마 매핑 검증 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>목적: 모든 {@code @Entity} 매핑이 Flyway 마이그레이션(V1~)이 적용한 실제 DDL과
 * {@code ddl-auto=validate} 정합함을 <b>단일 테스트로 빠르게</b> 보장한다.
 * Entity 신설·컬럼/타입 변경·마이그레이션 추가 직후 <b>전체 스위트보다 먼저</b> 단독 실행해
 * 매핑 불일치(예: smallint↔int)를 초 단위로 격리하기 위한 1차 관문이다.
 * 규칙: {@code docs/rules/schema-mapping-validation-rule.md}.
 *
 * <p>배경: Task 032에서 {@code reviews.rating}(smallint)을 Java {@code int}(INTEGER)로 매핑해
 * entityManagerFactory 빌드가 깨졌고, 리뷰와 무관한 실DB 통합 테스트 ~34개 클래스가 연쇄 실패했다.
 * 단일 컬럼 오류를 19분 전체 스위트로 찾던 것을 본 테스트가 수 초로 줄인다.
 *
 * <p>슬라이스 구성: 테스트 {@code application.yml}이 운영 자동설정을 광범위하게 제외하므로
 * {@code spring.autoconfigure.exclude=}로 리셋하고 Flyway를 활성화해 컨테이너에 운영과 동일한
 * 스키마(V1~)를 적용한다. {@code @DataJpaTest} 슬라이스는 화이트리스트 자동설정만 로드하므로
 * Kafka/Redis/Modulith 이벤트/web 등은 되살아나지 않는다. {@code @DataJpaTest}는 메인 앱 패키지
 * (com.shop.shop) 이하의 모든 {@code @Entity}를 스캔하므로 전 도메인 Entity가 검증 대상이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SchemaMappingValidationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private TestEntityManager em;

    /**
     * 컨텍스트가 기동했다는 것 자체가 ddl-auto=validate로 전 Entity가 Flyway DDL과 정합했다는 증거다.
     * (매핑 불일치가 있으면 entityManagerFactory 빌드 단계에서 컨텍스트 로드가 실패해 이 테스트에 도달하지 못한다.)
     * false-pass(Entity가 검증 대상에서 누락)를 막기 위해 메타모델에 다수 Entity가 등록됐음을 추가 단언한다.
     */
    @Test
    @DisplayName("모든 @Entity 매핑이 Flyway 마이그레이션 DDL과 validate 정합한다")
    void allEntitiesValidateAgainstFlywaySchema() {
        int entityCount = em.getEntityManager().getEntityManagerFactory().getMetamodel().getEntities().size();
        // 현재 도메인 Entity 28개 + Modulith event_publication 등. 큰 폭 감소는 스캔 누락(false-pass) 신호.
        assertThat(entityCount).isGreaterThanOrEqualTo(20);
    }
}
