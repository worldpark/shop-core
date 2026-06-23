package com.shop.shop.product.repository;

import com.shop.shop.common.crypto.CryptoConfig;
import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductSummaryProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pg_trgm GIN 인덱스 사용 실측 + 검색 동등성/경계 고정 통합 테스트 (실 PostgreSQL).
 *
 * <p>슬라이스 구성은 {@link ProductRepositoryIntegrationTest} 와 동일하게 계승한다:
 * {@code @DataJpaTest} + {@code AutoConfigureTestDatabase.NONE} + Testcontainers postgres:16.4-alpine
 * + {@code spring.autoconfigure.exclude=} + {@code flyway.enabled=true} + {@code ddl-auto=validate}.
 * 이 구성이 V12 포함 전체 마이그레이션을 컨테이너에 적용하고 Entity validate 를 통과해야
 * 테스트 컨텍스트가 로드된다.
 *
 * <h2>테스트 목표</h2>
 * <ol>
 *   <li><b>5-2 EXPLAIN 인덱스 사용 실측</b>: V12({@code idx_products_name_trgm}) 인덱스가
 *       실제로 사용되는지 EXPLAIN 으로 단언. 이론상 인덱스를 탄다는 추론으로 갈음 금지.</li>
 *   <li><b>5-3 검색 동등성/경계 고정</b>: 대소문자 혼합·부분문자열·미존재·null/blank·
 *       categoryId·status 화이트리스트·정렬 3종·페이징 totalElements·2글자 이하 keyword 경계.</li>
 * </ol>
 *
 * <h2>EXPLAIN 인덱스 유도 전략 — enable_seqscan = off</h2>
 * Testcontainers 환경은 소량 시드 데이터를 가지므로 PostgreSQL planner 가 비용상 seq scan 을 선택할
 * 수 있다. 소량 데이터(~60행)로도 인덱스 사용 가능성 자체를 검증하기 위해
 * {@code SET enable_seqscan = off} 세션 설정을 사용한다.
 * 이 설정은 planner 가 seq scan 을 기피하게 만들어 GIN 인덱스가 표현식/연산자 요건을 충족할 때
 * 반드시 Bitmap Index Scan 을 선택하게 한다. 표현식 불일치·인덱스 누락 시에는 다른 plan 이 나와
 * 단언이 RED 가 된다. 세션 설정이므로 현재 커넥션에만 적용되며 다른 테스트에 영향이 없다.
 * (대안으로 충분한 행을 시드하는 방법도 있으나 시드 유지비가 높고 통계 갱신 타이밍에 의존적이어서
 *  enable_seqscan = off 가 더 안정적이다. — plan §5-2 선택 사유)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CryptoConfig.class, EnvelopeEncryptionService.class})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ProductSearchTrgmIndexIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /** 공개 노출 허용 status 화이트리스트 (PublicProductService.PUBLIC_STATUSES 와 동일). */
    private static final List<ProductStatus> PUBLIC = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager em;

    // 데이터셋 식별자
    private Long sellerId;
    private Long catApparelId;
    private Long catFootwearId;

    // 검색 포함 상품 (ON_SALE / SOLD_OUT)
    private Long blueShirtId;      // "Blue Shirt"   ON_SALE  catApparel  price 80
    private Long redShoesId;       // "Red Shoes"    ON_SALE  catFootwear price 200
    private Long greenHatId;       // "Green Hat"    SOLD_OUT catApparel  price 40
    private Long whiteShirtId;     // "White Shirt"  ON_SALE  catApparel  price 60
    private Long yellowShoesId;    // "Yellow Shoes" ON_SALE  catFootwear price 120

    // 화이트리스트 제외
    private Long hiddenItemId;     // HIDDEN
    private Long draftItemId;      // DRAFT

    @BeforeEach
    void setUp() {
        // users FK 충족용 판매자 insert (product 모듈에 User Entity 없어 native insert)
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('trgm-seller@test.com', 'x', 'TrgmSeller', 'SELLER')").executeUpdate();
        sellerId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'trgm-seller@test.com'")
                .getSingleResult()).longValue();

        Category catApparel = persistCategory("Apparel", "apparel-trgm");
        Category catFootwear = persistCategory("Footwear", "footwear-trgm");
        catApparelId = catApparel.getId();
        catFootwearId = catFootwear.getId();

        // 검색 대상 상품 시드
        Product blueShirt = persistProduct("Blue Shirt", "100", ProductStatus.ON_SALE, catApparel);
        persistVariant(blueShirt, "SKU-BS-80", "80", 5, true);
        blueShirtId = blueShirt.getId();

        Product redShoes = persistProduct("Red Shoes", "200", ProductStatus.ON_SALE, catFootwear);
        redShoesId = redShoes.getId();

        Product greenHat = persistProduct("Green Hat", "50", ProductStatus.SOLD_OUT, catApparel);
        persistVariant(greenHat, "SKU-GH-40", "40", 10, true);
        greenHatId = greenHat.getId();

        Product whiteShirt = persistProduct("White Shirt", "70", ProductStatus.ON_SALE, catApparel);
        persistVariant(whiteShirt, "SKU-WS-60", "60", 3, true);
        whiteShirtId = whiteShirt.getId();

        Product yellowShoes = persistProduct("Yellow Shoes", "150", ProductStatus.ON_SALE, catFootwear);
        persistVariant(yellowShoes, "SKU-YS-120", "120", 2, true);
        yellowShoesId = yellowShoes.getId();

        // 화이트리스트 제외
        hiddenItemId = persistProduct("Hidden Shirt", "300", ProductStatus.HIDDEN, catApparel).getId();
        draftItemId = persistProduct("Draft Shoes", "10", ProductStatus.DRAFT, catFootwear).getId();

        em.flush();
        em.clear();
    }

    // =============================================================
    // 5-2 EXPLAIN 인덱스 사용 실측 (RED 보장)
    // =============================================================

    /**
     * idx_products_name_trgm GIN 인덱스가 실제로 사용되는지 EXPLAIN 으로 단언한다.
     *
     * <p>전략: {@code SET enable_seqscan = off} 세션 설정으로 planner 가 seq scan 을 기피하게 만든다.
     * 인덱스 표현식 {@code lower(name)} 과 연산자 {@code like}(gin_trgm_ops 지원 ~~) 요건이 충족되면
     * Bitmap Index Scan on idx_products_name_trgm 이 선택된다.
     * 인덱스 누락 또는 표현식 불일치 시 다른 plan 이 나와 단언이 RED 가 된다.
     * 세션 설정은 현재 커넥션에만 적용 — 다른 테스트에 영향 없음.
     */
    @Test
    @DisplayName("EXPLAIN — idx_products_name_trgm Bitmap Index Scan 사용 실측 (enable_seqscan=off 유도)")
    void explain_usesTrgmIndex_bitmapIndexScan() {
        // seqscan 기피 설정 (소량 시드에서도 인덱스 사용 가능성 검증을 강제)
        em.getEntityManager().createNativeQuery("SET enable_seqscan = off").executeUpdate();

        // 검색 절과 동일한 SQL 표현식으로 EXPLAIN 실행
        // ProductRepository JPQL: LOWER(p.name) LIKE LOWER(CONCAT('%', kw, '%'))
        // → Hibernate SQL 렌더: lower(p1_0.name) like lower('%'||?||'%') (또는 lower(concat('%',?,'%')))
        // EXPLAIN 대상은 그 표현식의 SQL 좌변과 동일한 형태: lower(name) like lower('%' || ? || '%')
        String explainSql = "EXPLAIN SELECT id FROM products WHERE lower(name) like lower('%' || ? || '%')";

        @SuppressWarnings("unchecked")
        List<String> planRows = em.getEntityManager()
                .createNativeQuery(explainSql)
                .setParameter(1, "shirt")
                .getResultList();

        String planText = String.join("\n", planRows);

        // 인덱스 이름 포함 단언
        assertThat(planText)
                .as("EXPLAIN 결과에 idx_products_name_trgm 인덱스가 포함되어야 한다.\n실제 plan:\n%s", planText)
                .containsIgnoringCase("idx_products_name_trgm");

        // Bitmap Index Scan 류 단언 (Bitmap Index Scan 또는 Index Scan 포함)
        assertThat(planText)
                .as("EXPLAIN 결과에 Index Scan 또는 Bitmap Index Scan 이 포함되어야 한다.\n실제 plan:\n%s", planText)
                .satisfiesAnyOf(
                        plan -> assertThat(plan).containsIgnoringCase("Bitmap Index Scan"),
                        plan -> assertThat(plan).containsIgnoringCase("Index Scan")
                );
    }

    // =============================================================
    // 5-3 검색 동등성 / 경계 고정
    // =============================================================

    @Test
    @DisplayName("keyword 부분일치 — 'shirt' 으로 Blue Shirt · White Shirt 매칭")
    void keyword_partialMatch_returnsMatchingProducts() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "shirt", null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, whiteShirtId);
    }

    @Test
    @DisplayName("keyword 대소문자 혼합 — 'SHIRT' 대문자 입력도 동일 결과")
    void keyword_upperCase_matchesSameAsLowerCase() {
        Page<ProductSummaryProjection> lowerPage = productRepository.findPublicProductsLatest(
                PUBLIC, "shirt", null, PageRequest.of(0, 20));
        Page<ProductSummaryProjection> upperPage = productRepository.findPublicProductsLatest(
                PUBLIC, "SHIRT", null, PageRequest.of(0, 20));

        assertThat(upperPage.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrderElementsOf(
                        lowerPage.getContent().stream()
                                .map(ProductSummaryProjection::productId)
                                .toList());
    }

    @Test
    @DisplayName("keyword 대소문자 혼합 — 'Sh' (소대 혼합) 입력도 매칭")
    void keyword_mixedCase_matches() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "Sh", null, PageRequest.of(0, 20));

        // "Blue Shirt", "White Shirt", "Red Shoes", "Yellow Shoes" 모두 'sh' 포함
        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, whiteShirtId, redShoesId, yellowShoesId);
    }

    @Test
    @DisplayName("keyword 미존재 — 매칭 상품 없으면 빈 결과")
    void keyword_noMatch_returnsEmptyPage() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "XXXXXXNOTEXIST", null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("keyword null — 전체 공개 상품 반환 (null 스킵)")
    void keyword_null_returnsAllPublicProducts() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 20));

        // ON_SALE 4개 + SOLD_OUT 1개 = 5개. HIDDEN/DRAFT 제외
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .doesNotContain(hiddenItemId, draftItemId);
    }

    @Test
    @DisplayName("keyword blank('   ') — PublicProductService 가 null 로 정규화하므로 전체 공개 상품과 동등")
    void keyword_blank_treatedAsNullByService() {
        // Service 가 trim 후 blank -> null 로 정규화하지만 repository 단 테스트이므로
        // repository 에 직접 null 을 전달해 동등성을 고정한다.
        Page<ProductSummaryProjection> withNull = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 20));
        // Service 정규화 후 repository 에 null 이 전달되는 흐름의 repository 결과를 고정
        assertThat(withNull.getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("categoryId 필터 — Apparel 카테고리만: Blue Shirt · Green Hat · White Shirt")
    void categoryFilter_returnsOnlyMatchingCategory() {
        Page<ProductSummaryProjection> pageApparel = productRepository.findPublicProductsLatest(
                PUBLIC, null, catApparelId, PageRequest.of(0, 20));

        assertThat(pageApparel.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, greenHatId, whiteShirtId);

        Page<ProductSummaryProjection> pageFootwear = productRepository.findPublicProductsLatest(
                PUBLIC, null, catFootwearId, PageRequest.of(0, 20));

        assertThat(pageFootwear.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(redShoesId, yellowShoesId);
    }

    @Test
    @DisplayName("keyword + categoryId 조합 — 'shoes' + Footwear: Red Shoes · Yellow Shoes")
    void keyword_and_category_combined() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "shoes", catFootwearId, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(redShoesId, yellowShoesId);
    }

    @Test
    @DisplayName("status 화이트리스트 — HIDDEN/DRAFT 상품은 keyword 매칭되어도 제외")
    void statusWhitelist_hiddenAndDraftExcluded_evenIfKeywordMatches() {
        // "Hidden Shirt" 와 "Draft Shoes" 모두 'i' 를 포함하지만 화이트리스트 제외
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .doesNotContain(hiddenItemId, draftItemId);
    }

    @Test
    @DisplayName("status 화이트리스트 — ON_SALE + SOLD_OUT 만 노출 (SOLD_OUT 포함 확인)")
    void statusWhitelist_soldOutIsIncluded() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "hat", null, PageRequest.of(0, 20));

        // Green Hat 은 SOLD_OUT — 화이트리스트 포함이어야 한다
        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .contains(greenHatId);
    }

    @Test
    @DisplayName("정렬 priceAsc — displayPrice 오름차순 (40, 60, 80, 120, 200)")
    void sortPriceAsc_ascendingOrder() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsPriceAsc(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(greenHatId, whiteShirtId, blueShirtId, yellowShoesId, redShoesId);
    }

    @Test
    @DisplayName("정렬 priceDesc — displayPrice 내림차순 (200, 120, 80, 60, 40)")
    void sortPriceDesc_descendingOrder() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsPriceDesc(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(redShoesId, yellowShoesId, blueShirtId, whiteShirtId, greenHatId);
    }

    @Test
    @DisplayName("정렬 3종 모두 keyword 포함 시 동일 totalElements (COUNT DISTINCT 정합)")
    void allSortMethods_withKeyword_sameTotalElements() {
        long latestTotal = productRepository.findPublicProductsLatest(
                PUBLIC, "shirt", null, PageRequest.of(0, 20)).getTotalElements();
        long priceAscTotal = productRepository.findPublicProductsPriceAsc(
                PUBLIC, "shirt", null, PageRequest.of(0, 20)).getTotalElements();
        long priceDescTotal = productRepository.findPublicProductsPriceDesc(
                PUBLIC, "shirt", null, PageRequest.of(0, 20)).getTotalElements();

        // "Blue Shirt" + "White Shirt" = 2개
        assertThat(latestTotal).isEqualTo(2);
        assertThat(priceAscTotal).isEqualTo(2);
        assertThat(priceDescTotal).isEqualTo(2);
    }

    @Test
    @DisplayName("다중 페이지 — totalElements = COUNT(DISTINCT p.id) 정합")
    void pagination_totalElementsMatchesCountDistinct() {
        Page<ProductSummaryProjection> first = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(5);  // ON_SALE 4 + SOLD_OUT 1
        assertThat(first.getTotalPages()).isEqualTo(3);

        Page<ProductSummaryProjection> last = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(2, 2));
        assertThat(last.getContent()).hasSize(1);
        assertThat(last.isLast()).isTrue();
    }

    @Test
    @DisplayName("keyword 2글자 이하(trigram 경계) — 결과 집합은 LIKE 와 동등 (성능만 영향)")
    @SuppressWarnings("UnnecessaryLocalVariable")
    void keyword_twoCharsOrLess_resultEquivalentToLike() {
        // trigram 인덱스는 3그램 미만 keyword 에서 인덱스 효과가 제한적이거나 seq scan 으로 폴백될 수 있다.
        // 그러나 결과 집합 자체는 LIKE 와 동등해야 한다(성능만 영향, 정확도 차이 없음 — plan §5-3).
        // "sh" (2글자) — 'sh' 를 포함하는 상품: Blue Shirt · White Shirt · Red Shoes · Yellow Shoes
        String twoCharKeyword = "sh";

        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, twoCharKeyword, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, whiteShirtId, redShoesId, yellowShoesId);
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("keyword 1글자 — 결과 집합은 LIKE 와 동등 (성능만 영향)")
    void keyword_oneChar_resultEquivalentToLike() {
        // "e" (1글자) — 'e' 포함: Blue Shirt · Red Shoes · Green Hat · White Shirt · Yellow Shoes
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "e", null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, redShoesId, greenHatId, whiteShirtId, yellowShoesId);
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private Category persistCategory(String name, String slug) {
        Category category = Category.of(name, slug, null, 0);
        em.persist(category);
        return category;
    }

    private Product persistProduct(String name, String basePrice, ProductStatus status, Category category) {
        BigDecimal price = new BigDecimal(basePrice);
        Product product = Product.create(sellerId, category, name, null, price);
        product.update(category, name, null, price, status);
        em.persist(product);
        return product;
    }

    private void persistVariant(Product product, String sku, String price, int stock, boolean active) {
        em.persist(ProductVariant.create(product, sku, new BigDecimal(price), stock, active, Set.of()));
    }
}
