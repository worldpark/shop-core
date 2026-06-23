package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountResponse;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.service.ProductSearchReindexService;
import com.shop.shop.product.service.ProductService;
import com.shop.shop.product.service.ReindexStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 풀 재색인·백필 통합 테스트 (T4 — 060).
 *
 * <p>059 {@code ProductSearchIndexIntegrationTest} 하니스를 계승:
 * PG(@ServiceConnection) + EmbeddedKafka + ES Nori({@link ImageFromDockerfile}) + {@code @DirtiesContext}.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>PG N건 시드 → reindex() → 새 인덱스 N건 + alias 새 인덱스 지시</li>
 *   <li>중간 실패(bulk 오류 주입) → alias 기존 유지 + 상태 FAILED</li>
 *   <li>재실행 멱등: 2회 연속 → 동일 최종 상태 + 정리(보존 개수 준수)</li>
 *   <li>bootstrap alias-centric: alias 존재 시 ensureIndex가 orphan products-v1 재생성 안 함</li>
 *   <li>keyset 경계: 페이지 크기 배수 + 마지막 페이지 — 전량 적재 누락 0</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EmbeddedKafka(topics = {
        ProductSearchIndexChangedEvent.TOPIC,
        ProductSearchIndexChangedEvent.TOPIC + ".DLQ",
        "order-completed", "payment-failed", "order-cancelled", "shipping-started",
        "member-registered", "password-reset-requested"
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
        "shop.search.indexer.enabled=true",
        "shop.order.pending-expiry.enabled=false",
        "shop.admin.dashboard.sse.enabled=false",
        "shop.seller.sales.sse.enabled=false",
        "shop.search.reindex.batch-size=3"   // 작은 배치로 keyset 경계 테스트
})
@DirtiesContext
class ProductSearchReindexIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Container
    @SuppressWarnings("resource")
    static final ElasticsearchContainer ELASTICSEARCH;

    static {
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
    private ProductSearchIndexAdmin admin;

    @Autowired
    private ProductSearchIndexBootstrap bootstrap;

    @Autowired
    private ProductSearchReindexService reindexService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long testOwnerId;

    @Autowired
    private JdbcTemplate jt;

    @BeforeEach
    void setUp() throws Exception {
        String email = "reindex-owner-" + UUID.randomUUID() + "@test.com";
        testOwnerId = jt.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES (?, 'x', 'ReindexOwner', 'SELLER') RETURNING id",
                Long.class, email);

        // 매 테스트 전 products-v* 인덱스 전부 삭제 후 bootstrap으로 초기 상태 복원
        deleteAllVersionIndices();
        bootstrap.ensureIndex();
    }

    // ========================================================================
    // 기본 재색인 검증
    // ========================================================================

    @Test
    @DisplayName("reindex: PG N건 시드 → 새 인덱스 N건 + alias가 새 인덱스를 가리킨다")
    void reindex_seedN_newIndexHasN_aliasPointsToNewIndex() throws Exception {
        // 상품 5건 시드 (다양한 status)
        long p1 = registerProduct("상품1", new BigDecimal("10000"));
        long p2 = registerProduct("상품2", new BigDecimal("20000"));
        long p3 = registerProduct("상품3", new BigDecimal("30000"));
        long p4 = registerProduct("상품4", new BigDecimal("40000"));
        long p5 = registerProduct("상품5", new BigDecimal("50000"));

        // 재색인 실행
        reindexService.reindex();

        // 상태 검증
        assertThat(reindexService.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        assertThat(reindexService.status().getProcessedCount()).isGreaterThanOrEqualTo(5L);

        String newIndex = reindexService.status().getNewIndex();
        assertThat(newIndex).startsWith("products-v");

        // 새 인덱스에 문서가 적재되었는지 확인
        elasticsearchClient.indices().refresh(r -> r.index(newIndex));
        CountResponse countResp = elasticsearchClient.count(r -> r.index(newIndex));
        assertThat(countResp.count()).isGreaterThanOrEqualTo(5L);

        // alias가 새 인덱스를 가리키는지 확인
        Set<String> indicesBehindAlias = admin.indicesBehindAlias();
        assertThat(indicesBehindAlias).contains(newIndex);
    }

    // ========================================================================
    // 중간 실패 → alias 기존 유지
    // ========================================================================

    @Test
    @DisplayName("중간 bulk 실패 → alias 기존 인덱스 유지 + 상태 FAILED")
    void reindex_midFailure_aliasUnchanged_statusFailed() throws Exception {
        // 초기 alias가 가리키는 인덱스 확인
        Set<String> originalIndices = admin.indicesBehindAlias();
        assertThat(originalIndices).isNotEmpty();
        String originalIndex = originalIndices.iterator().next();

        // 상품 1건 시드
        registerProduct("실패테스트상품", new BigDecimal("9900"));

        // admin.bulkIndex가 예외를 던지도록 spy — ProductSearchReindexService가 이미 admin을 주입받았으므로
        // 직접 spy를 사용할 수 없다. 대신 별도 service 인스턴스를 생성해 테스트
        ProductSearchIndexAdmin spyAdmin = Mockito.spy(admin);
        Mockito.doThrow(new IllegalStateException("bulk 강제 실패"))
                .when(spyAdmin).bulkIndex(Mockito.anyString(), Mockito.anyList());

        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSearchIndexAdmin> spyProvider =
                Mockito.mock(ObjectProvider.class);
        Mockito.when(spyProvider.getIfAvailable()).thenReturn(spyAdmin);

        ProductSearchReindexService failingService = new ProductSearchReindexService(
                spyProvider, productRepository, 3, 0L);

        failingService.reindex();

        // alias가 기존 인덱스를 여전히 가리키는지 확인
        Set<String> currentIndices = admin.indicesBehindAlias();
        assertThat(currentIndices).contains(originalIndex);

        // 상태 FAILED
        assertThat(failingService.status().getState()).isEqualTo(ReindexStatus.State.FAILED);

        failingService.destroy();
    }

    // ========================================================================
    // 재실행 멱등
    // ========================================================================

    @Test
    @DisplayName("재실행 멱등: 2회 연속 reindex → alias 최신 인덱스 지시 + 정리 정책(최대 2개 보존)")
    void reindex_twice_idempotent_cleanupPolicy() throws Exception {
        registerProduct("멱등테스트상품", new BigDecimal("15000"));

        // 첫 번째 재색인
        reindexService.reindex();
        assertThat(reindexService.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        String firstNewIndex = reindexService.status().getNewIndex();

        // 약간의 시간 간격 확보 (epoch millis 충돌 방지)
        Thread.sleep(2L);

        // 두 번째 재색인
        reindexService.reindex();
        assertThat(reindexService.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        String secondNewIndex = reindexService.status().getNewIndex();

        assertThat(secondNewIndex).isNotEqualTo(firstNewIndex);

        // alias가 두 번째 새 인덱스를 가리킴
        Set<String> currentIndices = admin.indicesBehindAlias();
        assertThat(currentIndices).contains(secondNewIndex);

        // 정리 정책: products-v* 인덱스 최대 2개 보존 (현재 + 직전 1개)
        List<String> remainingIndices = admin.listVersionIndices();
        assertThat(remainingIndices.size()).isLessThanOrEqualTo(2);
    }

    // ========================================================================
    // bootstrap alias-centric 검증
    // ========================================================================

    @Test
    @DisplayName("bootstrap alias-centric: alias 존재 상태에서 ensureIndex 호출 → orphan products-v1 재생성 없음")
    void bootstrap_aliasExists_ensureIndexNoOp() throws Exception {
        // 재색인 후 alias가 새 인덱스를 가리킴
        reindexService.reindex();
        String newIndex = reindexService.status().getNewIndex();

        // alias가 새 인덱스를 가리킴 확인
        Set<String> indicesBefore = admin.indicesBehindAlias();
        assertThat(indicesBefore).contains(newIndex);

        // ensureIndex 재호출 — alias가 있으므로 무동작
        bootstrap.ensureIndex();

        // alias가 여전히 새 인덱스를 가리킴 (products-v1 orphan 생성 없음)
        Set<String> indicesAfter = admin.indicesBehindAlias();
        assertThat(indicesAfter).contains(newIndex);
    }

    // ========================================================================
    // keyset 경계 검증
    // ========================================================================

    @Test
    @DisplayName("keyset 경계: 배치 크기의 배수 상품 수(6개, batch=3) → 전량 적재 누락 0")
    void reindex_keysetBoundary_exactMultipleOfBatch_noMissing() throws Exception {
        // batch-size=3 설정, 상품 6개 시드 → keyset 2배치 + 빈배치(종료)
        for (int i = 1; i <= 6; i++) {
            registerProduct("배치상품" + i, new BigDecimal(i * 1000));
        }

        reindexService.reindex();

        assertThat(reindexService.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        assertThat(reindexService.status().getProcessedCount()).isGreaterThanOrEqualTo(6L);

        String newIndex = reindexService.status().getNewIndex();
        elasticsearchClient.indices().refresh(r -> r.index(newIndex));
        CountResponse count = elasticsearchClient.count(r -> r.index(newIndex));
        assertThat(count.count()).isGreaterThanOrEqualTo(6L);
    }

    @Test
    @DisplayName("keyset 경계: 배치 크기보다 적은 마지막 페이지(7개, batch=3) → 전량 적재")
    void reindex_keysetBoundary_lastPagePartial_noMissing() throws Exception {
        // batch-size=3, 상품 7개 → 배치[3,3,1] → 마지막 부분 페이지 포함
        for (int i = 1; i <= 7; i++) {
            registerProduct("마지막배치상품" + i, new BigDecimal(i * 1000));
        }

        reindexService.reindex();

        assertThat(reindexService.status().getState()).isEqualTo(ReindexStatus.State.SUCCESS);
        assertThat(reindexService.status().getProcessedCount()).isGreaterThanOrEqualTo(7L);

        String newIndex = reindexService.status().getNewIndex();
        elasticsearchClient.indices().refresh(r -> r.index(newIndex));
        CountResponse count = elasticsearchClient.count(r -> r.index(newIndex));
        assertThat(count.count()).isGreaterThanOrEqualTo(7L);
    }

    // ========================================================================
    // 헬퍼
    // ========================================================================

    private long registerProduct(String name, BigDecimal basePrice) {
        Product saved = productService.register(testOwnerId, null, name, null, basePrice);
        return saved.getId();
    }

    private void deleteAllVersionIndices() {
        try {
            boolean exists = elasticsearchClient.indices()
                    .exists(r -> r.index("products-v*"))
                    .value();
            if (exists) {
                elasticsearchClient.indices().delete(r -> r.index("products-v*"));
            }
        } catch (Exception e) {
            // 없으면 무시
        }
    }
}
