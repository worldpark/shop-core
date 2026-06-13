package com.shop.shop.platform.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production application.yml의 Kafka producer value-serializer 회귀 가드.
 *
 * <p>근본 원인:
 * <ul>
 *   <li>{@code src/test/resources/application.yml}이 {@code src/main/resources/application.yml}을
 *       classpath shadow하므로, {@code @SpringBootTest}는 production app.yml의 serializer를 로드하지 않는다.</li>
 *   <li>이 테스트는 {@code ClassPathResource}가 아닌 <b>파일 경로 직접 읽기</b>로 production app.yml을
 *       읽어 회귀를 검증한다. Kafka/Testcontainers 불필요 — 순수 단위 테스트.</li>
 * </ul>
 *
 * <p>회귀 조건: production app.yml의 {@code spring.kafka.producer.value-serializer}가
 * {@code JsonSerializer}로 변경되면 <b>이 테스트가 RED</b>가 된다.
 * {@code JsonSerializer}는 byte[]를 base64로 이중 인코딩하는 버그를 일으킨다.
 */
class ProductionKafkaSerializerConfigTest {

    private static final String PRODUCTION_APP_YML = "src/main/resources/application.yml";
    private static final String EXPECTED_SERIALIZER = "org.apache.kafka.common.serialization.ByteArraySerializer";
    private static final String FORBIDDEN_SERIALIZER_TOKEN = "JsonSerializer";

    @Test
    @DisplayName("production application.yml의 kafka.producer.value-serializer는 ByteArraySerializer여야 한다")
    void production_kafka_producer_value_serializer_must_be_ByteArraySerializer() throws Exception {
        Path path = Path.of(PRODUCTION_APP_YML);
        assertThat(path.toFile()).as(
                "production application.yml 파일이 존재해야 한다: %s (gradle test 작업 디렉터리 = 모듈 루트)", path
        ).exists();

        String actualSerializer = resolveValueSerializer(path);

        assertThat(actualSerializer)
                .as("spring.kafka.producer.value-serializer는 ByteArraySerializer여야 한다. " +
                    "JsonSerializer 설정 시 byte[]가 base64로 이중 인코딩되는 버그 발생.")
                .isEqualTo(EXPECTED_SERIALIZER);
    }

    @Test
    @DisplayName("production application.yml의 kafka.producer.value-serializer에 JsonSerializer가 사용되지 않아야 한다")
    void production_kafka_producer_value_serializer_must_not_be_JsonSerializer() throws Exception {
        Path path = Path.of(PRODUCTION_APP_YML);
        String actualSerializer = resolveValueSerializer(path);

        assertThat(actualSerializer)
                .as("spring.kafka.producer.value-serializer에 JsonSerializer가 사용되면 " +
                    "byte[]가 base64로 이중 인코딩되는 버그가 발생한다.")
                .doesNotContain(FORBIDDEN_SERIALIZER_TOKEN);
    }

    /**
     * production application.yml을 SnakeYAML로 파싱해 {@code spring.kafka.producer.value-serializer} 값을 반환한다.
     */
    @SuppressWarnings("unchecked")
    private String resolveValueSerializer(Path path) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(path)) {
            Map<String, Object> root = yaml.load(is);

            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            assertThat(spring).as("spring 섹션이 존재해야 한다").isNotNull();

            Map<String, Object> kafka = (Map<String, Object>) spring.get("kafka");
            assertThat(kafka).as("spring.kafka 섹션이 존재해야 한다").isNotNull();

            Map<String, Object> producer = (Map<String, Object>) kafka.get("producer");
            assertThat(producer).as("spring.kafka.producer 섹션이 존재해야 한다").isNotNull();

            Object valueSerializer = producer.get("value-serializer");
            assertThat(valueSerializer)
                    .as("spring.kafka.producer.value-serializer 값이 존재해야 한다").isNotNull();

            return valueSerializer.toString();
        }
    }
}
