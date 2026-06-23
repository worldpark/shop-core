package com.shop.shop.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.dto.ProductSummaryProjection;
import com.shop.shop.product.event.ProductSearchIndexChangedEvent;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.service.PublicProductService;
import com.shop.shop.product.service.PublicProductSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 읽기 경로 통합 테스트 (Testcontainers ES Nori + PG).
 *
 * <p>059 {@code ProductSearchIndexIntegrationTest} 하니스 계승:
 * PG(@ServiceConnection) + EmbeddedKafka + ES Nori(ImageFromDockerfile) + @DirtiesContext.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>Nori 형태소 매칭 ("맥북케이스" ↔ "맥북 케이스")</li>
 *   <li>status 필터 (HIDDEN/DRAFT 항목 ES terms 필터 제외)</li>
 *   <li>페이징 (size=2 → content 2개, totalHits >= 5)</li>
 *   <li>드리프트 제거 (ES ON_SALE이나 PG HIDDEN → PG 재투영 시 제거)</li>
 *   <li>ES 비가용(쿨다운 강제) → PG pg_trgm 폴백 결과 반환</li>
 *   <li>keyword null → ES 미경유, PG 경로 동작 (회귀)</li>
 * </ul>
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
        // 쿨다운 0: 테스트에서 쿨다운 강제 해제 후 즉시 ES 재시도 가능하도록
        "shop.search.query.cooldown-ms=0",
        "shop.search.query.timeout-ms=5000"
})
@DirtiesContext
class ProductSearchQueryIntegrationTest {

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
    private ProductSearchIndexBootstrap bootstrap;

    @Autowired
    private ProductSearchIndexService productSearchIndexService;

    @Autowired
    private EsProductSearchAdapter esProductSearchAdapter;

    @Autowired
    private PublicProductService publicProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long testOwnerId;
    private Long catId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "search-query-test-" + UUID.randomUUID() + "@test.com";
        testOwnerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES (?, 'x', 'SearchQueryOwner', 'SELLER') RETURNING id",
                Long.class, email);

        // 인덱스 재생성 (멱등)
        if (elasticsearchClient.indices().exists(r -> r.index(ProductSearchIndexNames.CURRENT_INDEX)).value()) {
            elasticsearchClient.indices().delete(r -> r.index(ProductSearchIndexNames.CURRENT_INDEX));
        }
        bootstrap.ensureIndex();

        // 공유 카테고리 생성
        Category cat = Category.of("검색테스트카테고리", "search-test-cat-" + UUID.randomUUID(), null, 1);
        cat = categoryRepository.save(cat);
        catId = cat.getId();

        // 이전 테스트에서 남을 수 있는 쿨다운 초기화
        esProductSearchAdapter.setCooldownUntilEpochMs(0L);
    }

    // =============================================================
    // Nori 형태소 매칭
    // =============================================================

    @Test
    @DisplayName("Nori 형태소 매칭 — '맥북케이스' 검색 시 '맥북 케이스' 상품 반환")
    void search_noriMorphemeMatch_compoundWordMatchesSpaceSeparated() throws Exception {
        long productId = indexAndSaveProduct("맥북 케이스", "노트북 보호 케이스", "ON_SALE", new BigDecimal("25000"));
        refreshIndex();

        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "맥북케이스", catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(productId);
    }

    @Test
    @DisplayName("Nori 형태소 매칭 — '노트북' 검색 시 이름에 '노트북'이 포함된 상품만 반환")
    void search_noriMatch_basicKoreanKeyword() throws Exception {
        long laptopBag = indexAndSaveProduct("노트북 가방", "노트북 전용 가방", "ON_SALE", new BigDecimal("35000"));
        long wirelessMouse = indexAndSaveProduct("무선 마우스", "무선 마우스 제품", "ON_SALE", new BigDecimal("20000"));
        refreshIndex();

        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "노트북", catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(laptopBag)
                .doesNotContain(wirelessMouse);
    }

    // =============================================================
    // status 필터 — 비공개 제외
    // =============================================================

    @Test
    @DisplayName("status 필터 — HIDDEN 상품은 ES status terms 필터로 결과에서 제외")
    void search_statusFilter_hiddenExcluded() throws Exception {
        long publicId = indexAndSaveProduct("공개상품 필터테스트", "설명", "ON_SALE", new BigDecimal("10000"));
        long hiddenId = indexAndSaveProduct("비공개 필터테스트", "설명", "HIDDEN", new BigDecimal("5000"));
        refreshIndex();

        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "필터테스트", catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(publicId)
                .doesNotContain(hiddenId);
    }

    // =============================================================
    // 페이징
    // =============================================================

    @Test
    @DisplayName("페이징 — size=2이면 content 2개 반환, totalHits는 전체 수")
    void search_paging_sizeRespectsPageSize() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexAndSaveProduct("페이징테스트 상품" + i, "설명", "ON_SALE", new BigDecimal("10000"));
        }
        refreshIndex();

        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "페이징테스트", catId, PublicProductSort.LATEST, Pageable.ofSize(2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(5L);
    }

    // =============================================================
    // 드리프트 제거
    // =============================================================

    @Test
    @DisplayName("드리프트 제거 — ES ON_SALE 색인 후 PG HIDDEN으로 변경 시 결과에서 제거")
    void search_driftRemoval_pgHiddenDroppedFromEsResult() throws Exception {
        // ES에 ON_SALE로 색인
        long driftId = indexAndSaveProduct("드리프트테스트 상품", "설명", "ON_SALE", new BigDecimal("20000"));
        refreshIndex();

        // ES는 ON_SALE로 stale 상태 유지, PG만 HIDDEN으로 변경
        Product product = productRepository.findById(driftId).orElseThrow();
        Category cat = categoryRepository.findById(catId).orElseThrow();
        product.update(cat, product.getName(), product.getDescription(), product.getBasePrice(), ProductStatus.HIDDEN);
        productRepository.save(product);

        // ES terms filter는 ON_SALE이므로 통과하지만,
        // PG 재투영 단계에서 HIDDEN status → PUBLIC_STATUSES 화이트리스트 불일치 → 목록에서 제거
        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "드리프트테스트", catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .doesNotContain(driftId);
    }

    // =============================================================
    // ES 비가용 → PG 폴백
    // =============================================================

    @Test
    @DisplayName("ES 비가용(쿨다운 강제) — PG pg_trgm 폴백으로 키워드 검색 결과 반환")
    void search_esUnavailable_fallbackToPgTrgm() throws Exception {
        // PG에만 상품 저장 (ES 색인 없음 — pg_trgm LIKE 검색 대상)
        Category cat = categoryRepository.findById(catId).orElseThrow();
        Product p = Product.create(testOwnerId, cat, "폴백경로 상품", "설명", new BigDecimal("15000"));
        p.update(cat, "폴백경로 상품", "설명", new BigDecimal("15000"), ProductStatus.ON_SALE);
        productRepository.save(p);

        // 어댑터 쿨다운 강제 활성화 (ES 비가용 시뮬레이션)
        esProductSearchAdapter.setCooldownUntilEpochMs(System.currentTimeMillis() + 60_000L);

        // keyword 검색 시 ES 쿨다운 중 → PG pg_trgm 폴백 경로 사용
        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                "폴백경로", catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(p.getId());
    }

    // =============================================================
    // keyword null → PG 경로 (회귀)
    // =============================================================

    @Test
    @DisplayName("keyword null → ES 미경유, PG 목록 경로 동작 (회귀)")
    void search_noKeyword_usesPgPath_regression() throws Exception {
        Category cat = categoryRepository.findById(catId).orElseThrow();
        Product p = Product.create(testOwnerId, cat, "PG 목록 상품", "설명", new BigDecimal("30000"));
        p.update(cat, "PG 목록 상품", "설명", new BigDecimal("30000"), ProductStatus.ON_SALE);
        productRepository.save(p);

        // keyword=null → ES 경로 완전 미경유
        Page<ProductSummaryProjection> result = publicProductService.findPublicProducts(
                null, catId, PublicProductSort.LATEST, Pageable.ofSize(10));

        assertThat(result.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(p.getId());
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    /**
     * PG에 상품을 저장하고 ES에 직접 색인한다.
     *
     * <p>PublicProductService.findPublicProducts는 ES 결과 id로 PG 재투영을 수행하므로
     * PG 저장 없이는 재투영 결과가 비어 드리프트 제거로 오해될 수 있다.
     */
    private long indexAndSaveProduct(String name, String description, String status, BigDecimal price)
            throws Exception {
        Category cat = categoryRepository.findById(catId).orElseThrow();

        Product product = Product.create(testOwnerId, cat, name, description, price);
        if (!"DRAFT".equals(status)) {
            product.update(cat, name, description, price, ProductStatus.valueOf(status));
        }
        product = productRepository.save(product);
        long productId = product.getId();

        ProductSearchIndexChangedEvent event = new ProductSearchIndexChangedEvent(
                UUID.randomUUID(),
                Instant.now(),
                productId, name, description, catId, cat.getName(),
                status, price,
                "ON_SALE".equals(status) ? 1L : 0L
        );
        productSearchIndexService.upsert(event);
        return productId;
    }

    private void refreshIndex() throws Exception {
        elasticsearchClient.indices().refresh(r -> r.index(ProductSearchIndexNames.ALIAS));
    }
}
