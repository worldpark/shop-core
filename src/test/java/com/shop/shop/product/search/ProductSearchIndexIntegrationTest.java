package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.service.ProductService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 색인 이벤트 기반 indexer 통합 테스트.
 *
 * <p>PG + Kafka(EmbeddedKafka) + ES(Nori 내장 이미지) 전체 스택을 실엔진으로 검증한다.
 *
 * <p><b>Nori 이미지 전략</b>: {@code docker/shop/Dockerfile.search}를 Testcontainers
 * {@link ImageFromDockerfile}로 빌드해 analysis-nori가 설치된 이미지를 사용한다.
 * plain 공식 이미지({@code SearchClientConnectionIntegrationTest})로는 인덱스 생성이 실패하므로
 * 이 테스트는 반드시 Nori 이미지를 사용해야 한다.
 *
 * <p><b>{@code shop.search.indexer.enabled=true}</b> 프로퍼티로
 * {@link com.shop.shop.common.config.SearchIndexKafkaConsumerConfig}와
 * {@link ProductSearchIndexConsumer}가 활성화된다.
 *
 * <p><b>e2e 흐름 검증</b>:
 * <ul>
 *   <li>(e2e-a) {@code ProductService.register} → Outbox → Kafka → consumer → ES upsert (Awaitility 폴링)</li>
 *   <li>(e2e-b) dual-write 부재: register 시 product TX 커밋 + event_publication 행 존재 (ES 직접 쓰기 없음)</li>
 *   <li>(e2e-c) DLQ 격리: 독성 메시지가 {@code .DLQ} 토픽으로 라우팅됨</li>
 * </ul>
 *
 * <p><b>dual-write 부재 검증</b>: ES 없이 상품 등록 시 product TX가 커밋되고 {@code event_publication}에
 * 이벤트가 적재됨을 확인한다(ES 직접 쓰기 없음 — ADR-011).
 *
 * <p><b>Nori analyze</b>: {@code _analyze} API로 한국어 형태소 토큰화를 확인한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EmbeddedKafka(topics = {
        ProductSearchIndexChangedEvent.TOPIC,
        ProductSearchIndexChangedEvent.TOPIC + ".DLQ",
        "shop-core-smoke-test",
        "order-completed", "payment-failed", "order-cancelled", "shipping-started",
        "member-registered", "password-reset-requested"
})
@TestPropertySource(properties = {
        // test yml의 spring.autoconfigure.exclude를 완전 리셋(빈 값으로 교체) — ES + Kafka 자동설정 모두 활성화.
        // OutboxKafkaWireFormatTest 계승: Kafka 자동설정이 활성화되어야 KafkaTemplate 빈이 생성되고
        // spring.modulith.events.externalization이 kafkaEventExternalizer를 정상 생성할 수 있다.
        // @EmbeddedKafka + spring.kafka.bootstrap-servers로 EmbeddedKafka 브로커를 가리킨다.
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
        "shop.search.indexer.enabled=true",   // SearchIndexKafkaConsumerConfig + ProductSearchIndexConsumer + ProductSearchIndexConfig 활성화
        "shop.order.pending-expiry.enabled=false",
        "shop.admin.dashboard.sse.enabled=false",
        "shop.seller.sales.sse.enabled=false"
})
@DirtiesContext
class ProductSearchIndexIntegrationTest {

    /** PG — @ServiceConnection 자동 url/username/password 주입. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /**
     * ES — Nori 내장 이미지. {@code Dockerfile.search}를 Testcontainers {@link ImageFromDockerfile}로 빌드.
     * analysis-nori가 설치된 이미지가 필요하므로 plain 공식 이미지 불가.
     *
     * <p>{@code ImageFromDockerfile.get()}으로 이미지를 동기 빌드한 뒤 그 이미지명을
     * {@code DockerImageName.asCompatibleSubstituteFor}로 {@link ElasticsearchContainer}에 주입한다.
     * {@link ElasticsearchContainer} 생성자는 {@code DockerImageName} 또는 {@code String}만 허용하므로
     * {@code ImageFromDockerfile} 직접 전달이 불가하다(컴파일 에러). 이 패턴이 Testcontainers 공식 권장.
     */
    @Container
    @SuppressWarnings("resource")
    static final ElasticsearchContainer ELASTICSEARCH;

    static {
        // Nori Dockerfile을 build → 이미지명 확정 → ElasticsearchContainer에 주입
        String builtImageName = new ImageFromDockerfile("shop-search-nori-test", false)
                .withDockerfile(Paths.get("../docker/shop/Dockerfile.search").toAbsolutePath())
                .get();
        ELASTICSEARCH = new ElasticsearchContainer(
                DockerImageName.parse(builtImageName)
                        .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch")
        ).withEnv("xpack.security.enabled", "false");
    }

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ProductSearchIndexBootstrap bootstrap;

    @Autowired
    private ProductSearchIndexService productSearchIndexService;

    /** e2e: 도메인 진입점 서비스 */
    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ObjectMapper objectMapper;

    /** e2e 테스트용 owner userId — @BeforeEach에서 users 테이블 INSERT 후 생성된 id를 저장 */
    private long testOwnerId;

    @BeforeEach
    void setUp() throws Exception {
        // e2e 테스트용 owner — products.owner_id FK(users.id) 충족
        // GENERATED ALWAYS AS IDENTITY이므로 INSERT 후 반환된 id를 사용한다.
        // 동일 이메일이 이미 있으면 기존 id를 조회해 재사용(테스트 격리 불완전 시 대비).
        String email = "e2e-owner-" + java.util.UUID.randomUUID() + "@test.com";
        testOwnerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES (?, 'x', 'E2EOwner', 'SELLER') RETURNING id",
                Long.class,
                email
        );

        // 매 테스트 전 인덱스 재생성(멱등 검증)
        if (elasticsearchClient.indices().exists(r -> r.index(ProductSearchIndexNames.CURRENT_INDEX)).value()) {
            elasticsearchClient.indices().delete(r -> r.index(ProductSearchIndexNames.CURRENT_INDEX));
        }
        bootstrap.ensureIndex();
    }

    // ============================================================
    // 부트스트랩 멱등
    // ============================================================

    @Test
    @DisplayName("부트스트랩 멱등: 2회 ensureIndex 호출해도 오류 없음")
    void bootstrap_idempotent_doubleCall_noError() throws Exception {
        bootstrap.ensureIndex(); // 2회 호출
        assertThat(elasticsearchClient.indices()
                .exists(r -> r.index(ProductSearchIndexNames.CURRENT_INDEX)).value()).isTrue();
    }

    @Test
    @DisplayName("부트스트랩: alias products → products-v1 존재")
    void bootstrap_aliasExists() throws Exception {
        assertThat(elasticsearchClient.indices()
                .existsAlias(r -> r.name(ProductSearchIndexNames.ALIAS)).value()).isTrue();
    }

    // ============================================================
    // Nori _analyze
    // ============================================================

    @Test
    @DisplayName("Nori analyze: '맥북 케이스' 토큰에 '맥북'과 '케이스' 포함")
    void noriAnalyze_koreanCompound_tokenized() throws Exception {
        var result = elasticsearchClient.indices().analyze(r -> r
                .index(ProductSearchIndexNames.CURRENT_INDEX)
                .analyzer("korean_nori")
                .text("맥북 케이스")
        );
        var tokens = result.tokens().stream()
                .map(t -> t.token())
                .toList();
        assertThat(tokens).contains("맥북", "케이스");
    }

    @Test
    @DisplayName("Nori analyze: '맥북케이스' 합성어 분해 — mixed 모드로 원형+분해 토큰 포함")
    void noriAnalyze_compoundWord_decomposes() throws Exception {
        var result = elasticsearchClient.indices().analyze(r -> r
                .index(ProductSearchIndexNames.CURRENT_INDEX)
                .analyzer("korean_nori")
                .text("맥북케이스")
        );
        var tokens = result.tokens().stream()
                .map(t -> t.token())
                .toList();
        // decompound_mode=mixed: 원형("맥북케이스") + 분해("맥북", "케이스") 모두 포함
        assertThat(tokens).contains("맥북", "케이스");
    }

    // ============================================================
    // upsert — external version 멱등
    // ============================================================

    @Test
    @DisplayName("upsert: productId가 _id가 되고 문서가 ES에 색인된다")
    void upsert_documentIndexed() throws Exception {
        ProductSearchIndexChangedEvent event = sampleEvent(100L, "테스트 상품", "ON_SALE",
                new BigDecimal("10000"), 2L);
        productSearchIndexService.upsert(event);

        // 인덱스 refresh (테스트 환경)
        elasticsearchClient.indices().refresh(r -> r.index(ProductSearchIndexNames.ALIAS));

        GetResponse<ProductSearchDocument> response = elasticsearchClient.get(
                r -> r.index(ProductSearchIndexNames.ALIAS).id("100"),
                ProductSearchDocument.class
        );
        assertThat(response.found()).isTrue();
        assertThat(response.source().name()).isEqualTo("테스트 상품");
        assertThat(response.source().status()).isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("upsert: 동일 productId에 최신 버전 이벤트는 문서 갱신, 옛 버전 이벤트는 swallow(version conflict)")
    void upsert_externalVersion_outOfOrderRejected() throws Exception {
        // 최신 이벤트 먼저 색인
        ProductSearchIndexChangedEvent newer = sampleEventWithTime(200L, "신상품", "ON_SALE",
                new BigDecimal("20000"), 1L, java.time.Instant.now());
        productSearchIndexService.upsert(newer);

        // 옛 이벤트(낮은 version) — version conflict → swallow
        ProductSearchIndexChangedEvent older = sampleEventWithTime(200L, "구상품", "DRAFT",
                new BigDecimal("5000"), 0L, java.time.Instant.now().minusSeconds(60));
        org.assertj.core.api.Assertions.assertThatCode(() -> productSearchIndexService.upsert(older))
                .doesNotThrowAnyException();

        elasticsearchClient.indices().refresh(r -> r.index(ProductSearchIndexNames.ALIAS));

        // 문서는 최신 이벤트 기준
        GetResponse<ProductSearchDocument> response = elasticsearchClient.get(
                r -> r.index(ProductSearchIndexNames.ALIAS).id("200"),
                ProductSearchDocument.class
        );
        assertThat(response.source().name()).isEqualTo("신상품");
        assertThat(response.source().status()).isEqualTo("ON_SALE");
    }

    // ============================================================
    // e2e (a): ProductService.register → Outbox → Kafka → consumer → ES
    // ============================================================

    @Test
    @DisplayName("e2e(a): ProductService.register → Outbox → Kafka → consumer → ES 문서 upsert (Awaitility 폴링)")
    void e2e_register_productsDocumentUpsertedInES() throws Exception {
        String productName = "e2e-상품-" + UUID.randomUUID();

        // 진입점 서비스 호출 (실제 e2e 경로: TX 커밋 → Outbox → Kafka 외부화 → consumer → ES)
        Product saved = productService.register(testOwnerId, null, productName, "e2e 테스트", new BigDecimal("15000"));

        long productId = saved.getId();

        // Awaitility: consumer가 Kafka 메시지를 수신 후 ES에 색인될 때까지 폴링 (최대 30초)
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    elasticsearchClient.indices().refresh(r -> r.index(ProductSearchIndexNames.ALIAS));
                    GetResponse<ProductSearchDocument> response = elasticsearchClient.get(
                            r -> r.index(ProductSearchIndexNames.ALIAS).id(String.valueOf(productId)),
                            ProductSearchDocument.class
                    );
                    assertThat(response.found())
                            .as("상품 %d가 ES alias 'products'에 색인되어야 한다", productId)
                            .isTrue();
                    assertThat(response.source().name())
                            .as("ES 문서 name이 등록된 상품명과 일치해야 한다")
                            .isEqualTo(productName);
                });
    }

    // ============================================================
    // e2e (b): dual-write 부재 — product TX 커밋 + event_publication 행 존재
    // ============================================================

    @Test
    @DisplayName("e2e(b): dual-write 부재 — register 시 product TX 커밋 + event_publication 행 적재 (ES 직접 쓰기 없음)")
    void e2e_register_dualWriteAbsence_productAndEventPublicationExist() {
        String productName = "dual-write-test-" + UUID.randomUUID();

        // register 호출 — ES와 직접 통신하지 않고 Outbox에만 적재
        Product saved = productService.register(testOwnerId, null, productName, null, new BigDecimal("9900"));

        long productId = saved.getId();

        // (1) product TX 커밋 검증: PG에 상품이 존재해야 한다
        assertThat(productRepository.findById(productId))
                .as("product가 PG에 커밋되어야 한다")
                .isPresent();

        // (2) event_publication 행 존재 검증: Outbox에 이벤트가 적재되어야 한다
        // Modulith가 event_publication 테이블에 INCOMPLETE 상태로 저장 후 커밋 뒤 외부화한다.
        // 외부화가 즉시 완료되면 COMPLETED 상태이므로 productId로 페이로드 내용을 검색한다.
        int rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class,
                "%\"productId\":" + productId + "%"
        );
        assertThat(rowCount)
                .as("event_publication에 productId=%d인 이벤트 행이 존재해야 한다 (dual-write 없음 구조적 보장)", productId)
                .isGreaterThanOrEqualTo(1);
    }

    // ============================================================
    // e2e (c): DLQ 격리 — 독성 메시지가 .DLQ로 라우팅됨
    // ============================================================

    @Test
    @DisplayName("e2e(c): DLQ 격리 — 독성(역직렬화 불가) 메시지가 product-search-index-changed.DLQ로 라우팅됨")
    void e2e_dlq_poisonMessageRoutedToDlq() throws Exception {
        String dlqTopic = ProductSearchIndexChangedEvent.TOPIC + ".DLQ";

        // DLQ 토픽 컨슈머 준비
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlq-test-group-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, String> dlqConsumer =
                     new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dlqConsumer, dlqTopic);

            // 독성 메시지 발행: 역직렬화 불가 garbage payload
            Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            // 누수 방지: producer factory를 명시적으로 close 하지 않으면, @DirtiesContext로 EmbeddedKafka
            // 브로커가 종료된 뒤에도 이 producer가 무한 재연결을 시도하며 풀 병렬 스위트에서 fork 종료를 막는다.
            DefaultKafkaProducerFactory<String, String> poisonProducerFactory =
                    new DefaultKafkaProducerFactory<>(producerProps);
            try {
                KafkaTemplate<String, String> producer = new KafkaTemplate<>(poisonProducerFactory);

                String poisonPayload = "THIS_IS_NOT_VALID_JSON_{{{{";
                producer.send(new ProducerRecord<>(ProductSearchIndexChangedEvent.TOPIC, "poison-key", poisonPayload))
                        .get(5, TimeUnit.SECONDS);

                // Awaitility: DLQ에 메시지가 도착할 때까지 폴링 (최대 30초)
                Awaitility.await()
                        .atMost(30, TimeUnit.SECONDS)
                        .pollInterval(Duration.ofMillis(500))
                        .untilAsserted(() -> {
                            ConsumerRecords<String, String> dlqRecords =
                                    KafkaTestUtils.getRecords(dlqConsumer, Duration.ofMillis(500));
                            assertThat(dlqRecords.count())
                                    .as("독성 메시지가 DLQ 토픽 '%s'으로 라우팅되어야 한다", dlqTopic)
                                    .isGreaterThanOrEqualTo(1);
                        });
            } finally {
                poisonProducerFactory.destroy();
            }
        }
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private ProductSearchIndexChangedEvent sampleEvent(long productId, String name, String status,
                                                        BigDecimal displayPrice, long purchasable) {
        return sampleEventWithTime(productId, name, status, displayPrice, purchasable, java.time.Instant.now());
    }

    private ProductSearchIndexChangedEvent sampleEventWithTime(long productId, String name, String status,
                                                                 BigDecimal displayPrice, long purchasable,
                                                                 java.time.Instant occurredAt) {
        return new ProductSearchIndexChangedEvent(
                java.util.UUID.randomUUID(),
                occurredAt,
                productId, name, null, null, null,
                status, displayPrice, purchasable
        );
    }
}
