package com.shop.shop.product.repository;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ProductRepository} 공개 상품 목록 집계 쿼리 통합 테스트 (실 PostgreSQL).
 *
 * <p>이 쿼리들은 PostgreSQL 고유 동작(특히 NULL 파라미터 타입 추론)에 의존한다.
 * H2로는 재현되지 않는 결함이 있으므로 Testcontainers로 운영과 동일한
 * postgres:16.4-alpine 컨테이너를 띄워 실제 SQL 실행을 검증한다.
 * (testing-rule.md §슬라이스·프로파일 — "PostgreSQL ↔ H2 DB 종류 차이 점검")
 *
 * <p>핵심 회귀: keyword가 null일 때 드라이버가 타입 미상 파라미터를 bytea로 전송 →
 * {@code lower(bytea) does not exist} 오류. repository가 {@code CAST(:keyword AS string)}로
 * 타입을 고정해 이를 방지하는지 검증한다. 이 테스트는 수정 전 코드에서 반드시 실패한다.
 *
 * <p>슬라이스 구성: 테스트 {@code application.yml}이 운영 자동설정을 광범위하게 제외하므로
 * {@code spring.autoconfigure.exclude=}로 리셋하고 Flyway를 활성화해 컨테이너에
 * 운영과 동일한 스키마(V1~V3)를 적용한다. {@code @DataJpaTest} 슬라이스는 화이트리스트
 * 자동설정만 로드하므로 Kafka/Redis/Modulith 이벤트 등은 되살아나지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ProductRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /** 공개 노출 허용 status 화이트리스트 (Service 규칙과 동일). */
    private static final List<ProductStatus> PUBLIC = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager em;

    // 데이터셋 식별자 — @BeforeEach에서 채움
    private Long ownerId;
    private Long catAId;
    private Long catBId;
    private Long blueShirtId;   // ON_SALE, catA, displayPrice 80, purchasable 1
    private Long redShoesId;    // ON_SALE, catB, variant 없음 → displayPrice = basePrice 200, purchasable 0
    private Long greenHatId;    // SOLD_OUT, catA, displayPrice 40, purchasable 1
    private Long hiddenItemId;  // HIDDEN  → 화이트리스트 제외
    private Long draftItemId;   // DRAFT   → 화이트리스트 제외

    @BeforeEach
    void setUp() {
        // owner FK(users.id) 충족용 사용자 1명 — product 모듈에 User Entity가 없어 native insert 사용
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('seller@test.com', 'x', 'Seller', 'SELLER')").executeUpdate();
        ownerId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'seller@test.com'")
                .getSingleResult()).longValue();

        Category catA = persistCategory("Apparel", "apparel");
        Category catB = persistCategory("Footwear", "footwear");
        catAId = catA.getId();
        catBId = catB.getId();

        // p1: 활성 variant 2개(가격 90/재고5, 가격 80/재고0) + 비활성 variant(가격 50/재고100)
        //     displayPrice = min(90,80)=80, purchasable = 1 (재고0·비활성 제외)
        Product blueShirt = persistProduct("Blue Shirt", "100", ProductStatus.ON_SALE, catA);
        persistVariant(blueShirt, "SKU-SHIRT-90", "90", 5, true);
        persistVariant(blueShirt, "SKU-SHIRT-80", "80", 0, true);
        persistVariant(blueShirt, "SKU-SHIRT-50", "50", 100, false);
        blueShirtId = blueShirt.getId();

        // p2: variant 없음 → displayPrice = basePrice 200, purchasable 0
        Product redShoes = persistProduct("Red Shoes", "200", ProductStatus.ON_SALE, catB);
        redShoesId = redShoes.getId();

        // p3: SOLD_OUT(화이트리스트 포함) + 활성 variant(가격 40/재고10)
        Product greenHat = persistProduct("Green Hat", "50", ProductStatus.SOLD_OUT, catA);
        persistVariant(greenHat, "SKU-HAT-40", "40", 10, true);
        greenHatId = greenHat.getId();

        // p4/p5: 화이트리스트 제외 status
        hiddenItemId = persistProduct("Hidden Item", "300", ProductStatus.HIDDEN, catA).getId();
        draftItemId = persistProduct("Draft Item", "10", ProductStatus.DRAFT, catB).getId();

        em.flush();
        em.clear();
    }

    // =============================================================
    // 회귀: NULL keyword (bytea 오류 방지)
    // =============================================================

    @Test
    @DisplayName("keyword=null 최신순 — bytea 오류 없이 화이트리스트 상품만 id DESC로 반환")
    void findPublicProductsLatest_nullKeyword_doesNotFailWithByteaError() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 20));

        // 동일 트랜잭션 insert → created_at 동일 → id DESC 타이브레이크 (p3 > p2 > p1)
        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(greenHatId, redShoesId, blueShirtId);
    }

    @Test
    @DisplayName("keyword=null — 3개 정렬 메서드 모두 SQLGrammarException 없이 실행된다")
    void allSortMethods_nullKeyword_executeWithoutError() {
        var pageable = PageRequest.of(0, 20);

        assertThatCode(() -> {
            assertThat(productRepository.findPublicProductsLatest(PUBLIC, null, null, pageable)).hasSize(3);
            assertThat(productRepository.findPublicProductsPriceAsc(PUBLIC, null, null, pageable)).hasSize(3);
            assertThat(productRepository.findPublicProductsPriceDesc(PUBLIC, null, null, pageable)).hasSize(3);
        }).doesNotThrowAnyException();
    }

    // =============================================================
    // keyword 필터 (부분 일치 + 대소문자 무시)
    // =============================================================

    @Test
    @DisplayName("keyword 'sh' — 대소문자 무시 부분 일치 (Blue Shirt, Red Shoes)")
    void keyword_caseInsensitivePartialMatch() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "sh", null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, redShoesId);
    }

    @Test
    @DisplayName("keyword 'SHIRT' — 대문자 입력도 매칭 (Blue Shirt)")
    void keyword_upperCaseInput_matches() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, "SHIRT", null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(blueShirtId);
    }

    // =============================================================
    // category 필터
    // =============================================================

    @Test
    @DisplayName("categoryId 필터 — 해당 카테고리의 화이트리스트 상품만 (HIDDEN 제외)")
    void categoryFilter_returnsOnlyVisibleInCategory() {
        Page<ProductSummaryProjection> pageA = productRepository.findPublicProductsLatest(
                PUBLIC, null, catAId, PageRequest.of(0, 20));
        // catA: Blue Shirt(ON_SALE), Green Hat(SOLD_OUT), Hidden Item(HIDDEN→제외)
        assertThat(pageA.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrder(blueShirtId, greenHatId);

        Page<ProductSummaryProjection> pageB = productRepository.findPublicProductsLatest(
                PUBLIC, null, catBId, PageRequest.of(0, 20));
        // catB: Red Shoes(ON_SALE), Draft Item(DRAFT→제외)
        assertThat(pageB.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(redShoesId);
    }

    // =============================================================
    // status 화이트리스트
    // =============================================================

    @Test
    @DisplayName("status 화이트리스트 — DRAFT/HIDDEN 상품은 결과에서 제외된다")
    void statusWhitelist_excludesDraftAndHidden() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .doesNotContain(hiddenItemId, draftItemId)
                .containsExactlyInAnyOrder(blueShirtId, redShoesId, greenHatId);
    }

    // =============================================================
    // displayPrice = COALESCE(MIN(활성 variant price), basePrice)
    // =============================================================

    @Test
    @DisplayName("displayPrice — 활성 variant가 있으면 MIN(price), 없으면 basePrice 폴백")
    void displayPrice_coalesceMinActiveVariantPriceOrBasePrice() {
        Map<Long, ProductSummaryProjection> byId = fetchAllById();

        // Blue Shirt: 활성 variant 가격 90/80 중 MIN=80 (비활성 50은 제외)
        assertThat(byId.get(blueShirtId).displayPrice()).isEqualByComparingTo("80");
        // Red Shoes: variant 없음 → basePrice 200 폴백
        assertThat(byId.get(redShoesId).displayPrice()).isEqualByComparingTo("200");
        // Green Hat: 활성 variant 40
        assertThat(byId.get(greenHatId).displayPrice()).isEqualByComparingTo("40");
    }

    // =============================================================
    // purchasableVariantCount = 활성 && stock>0
    // =============================================================

    @Test
    @DisplayName("purchasableVariantCount — 활성이고 재고>0인 variant만 집계")
    void purchasableVariantCount_countsActiveInStockOnly() {
        Map<Long, ProductSummaryProjection> byId = fetchAllById();

        // Blue Shirt: 가격90/재고5(O), 가격80/재고0(X), 비활성(X) → 1
        assertThat(byId.get(blueShirtId).purchasableVariantCount()).isEqualTo(1);
        assertThat(byId.get(blueShirtId).hasPurchasableVariant()).isTrue();
        // Red Shoes: variant 없음 → 0
        assertThat(byId.get(redShoesId).purchasableVariantCount()).isZero();
        assertThat(byId.get(redShoesId).hasPurchasableVariant()).isFalse();
        // Green Hat: 활성/재고10 → 1
        assertThat(byId.get(greenHatId).purchasableVariantCount()).isEqualTo(1);
    }

    // =============================================================
    // 정렬
    // =============================================================

    @Test
    @DisplayName("priceAsc — displayPrice 오름차순 (40, 80, 200)")
    void sortPriceAsc() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsPriceAsc(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(greenHatId, blueShirtId, redShoesId);
    }

    @Test
    @DisplayName("priceDesc — displayPrice 내림차순 (200, 80, 40)")
    void sortPriceDesc() {
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsPriceDesc(
                PUBLIC, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(redShoesId, blueShirtId, greenHatId);
    }

    // =============================================================
    // 페이징 + countQuery (GROUP BY totalElements 정합)
    // =============================================================

    @Test
    @DisplayName("페이징 — GROUP BY 집계에도 totalElements는 DISTINCT 상품 수와 일치")
    void pagination_countQueryReflectsDistinctProducts() {
        Page<ProductSummaryProjection> first = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(3);  // p1,p2,p3 (variant join으로 부풀지 않음)
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(greenHatId, redShoesId);

        Page<ProductSummaryProjection> second = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(1, 2));
        assertThat(second.getContent())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(blueShirtId);
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private Map<Long, ProductSummaryProjection> fetchAllById() {
        return productRepository.findPublicProductsLatest(PUBLIC, null, null, PageRequest.of(0, 20))
                .getContent().stream()
                .collect(Collectors.toMap(ProductSummaryProjection::productId, p -> p));
    }

    private Category persistCategory(String name, String slug) {
        Category category = Category.of(name, slug, null, 0);
        em.persist(category);
        return category;
    }

    private Product persistProduct(String name, String basePrice, ProductStatus status, Category category) {
        BigDecimal price = new BigDecimal(basePrice);
        Product product = Product.create(ownerId, category, name, null, price);
        // create()는 status를 DRAFT로 강제하므로 의도한 status로 교체
        product.update(category, name, null, price, status);
        em.persist(product);
        return product;
    }

    private void persistVariant(Product product, String sku, String price, int stock, boolean active) {
        em.persist(ProductVariant.create(product, sku, new BigDecimal(price), stock, active, Set.of()));
    }
}
