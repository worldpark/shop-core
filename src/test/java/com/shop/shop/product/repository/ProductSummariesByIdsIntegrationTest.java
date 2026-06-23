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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductRepository#findPublicProductSummariesByIds} 통합 테스트 (실 PostgreSQL).
 *
 * <p>ES 읽기 경로 전용 재투영 쿼리 검증:
 * <ul>
 *   <li>displayPrice/purchasableVariantCount가 {@code findPublicProductsLatest}와 동일한 집계 식</li>
 *   <li>WHERE p.id IN :ids AND p.status IN :statuses — 드리프트 status 항목 제거</li>
 *   <li>ORDER BY 없음 — 반환 순서는 DB 의존(호출자가 ES 순서로 재정렬)</li>
 * </ul>
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
class ProductSummariesByIdsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /** 공개 노출 허용 status 화이트리스트 (PublicProductService.PUBLIC_STATUSES와 동일). */
    private static final List<ProductStatus> PUBLIC = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager em;

    private Long ownerId;
    private Long blueShirtId;   // ON_SALE, displayPrice=80, purchasable=1
    private Long redShoesId;    // ON_SALE, variant 없음 → displayPrice=basePrice(200), purchasable=0
    private Long greenHatId;    // SOLD_OUT, displayPrice=40, purchasable=1
    private Long hiddenItemId;  // HIDDEN → 드리프트 제거 대상
    private Long draftItemId;   // DRAFT → 드리프트 제거 대상

    @BeforeEach
    void setUp() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('seller@test.com', 'x', 'Seller', 'SELLER')").executeUpdate();
        ownerId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'seller@test.com'")
                .getSingleResult()).longValue();

        Category cat = persistCategory("Electronics", "electronics");

        // p1: 활성 variant 2개(가격90/재고5, 가격80/재고0) + 비활성 variant(가격50/재고100)
        //     displayPrice=min(90,80)=80, purchasable=1
        Product blueShirt = persistProduct("Blue Shirt", "100", ProductStatus.ON_SALE, cat);
        persistVariant(blueShirt, "SKU-SHIRT-90", "90", 5, true);
        persistVariant(blueShirt, "SKU-SHIRT-80", "80", 0, true);
        persistVariant(blueShirt, "SKU-SHIRT-50", "50", 100, false);
        blueShirtId = blueShirt.getId();

        // p2: variant 없음 → displayPrice=basePrice=200, purchasable=0
        Product redShoes = persistProduct("Red Shoes", "200", ProductStatus.ON_SALE, cat);
        redShoesId = redShoes.getId();

        // p3: SOLD_OUT + 활성 variant(가격40/재고10) → displayPrice=40, purchasable=1
        Product greenHat = persistProduct("Green Hat", "50", ProductStatus.SOLD_OUT, cat);
        persistVariant(greenHat, "SKU-HAT-40", "40", 10, true);
        greenHatId = greenHat.getId();

        // p4/p5: 드리프트 대상 (화이트리스트 밖 status)
        hiddenItemId = persistProduct("Hidden Item", "300", ProductStatus.HIDDEN, cat).getId();
        draftItemId = persistProduct("Draft Item", "10", ProductStatus.DRAFT, cat).getId();

        em.flush();
        em.clear();
    }

    // =============================================================
    // 기본 동작 — IDs로 공개 상품 집계
    // =============================================================

    @Test
    @DisplayName("공개 상품만 포함 — ON_SALE/SOLD_OUT 3종 모두 집계됨")
    void findPublicProductSummariesByIds_allPublicIds_returnsAll() {
        List<Long> ids = List.of(blueShirtId, redShoesId, greenHatId);

        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(ids, PUBLIC);

        assertThat(result).hasSize(3);
        Map<Long, ProductSummaryProjection> byId = result.stream()
                .collect(Collectors.toMap(ProductSummaryProjection::productId, Function.identity()));
        assertThat(byId).containsKeys(blueShirtId, redShoesId, greenHatId);
    }

    // =============================================================
    // displayPrice 집계 — findPublicProductsLatest와 동일 식
    // =============================================================

    @Test
    @DisplayName("displayPrice — 활성 variant 최소가격 COALESCE 계산 (blueShirt: min=80)")
    void findPublicProductSummariesByIds_displayPrice_minActiveVariantPrice() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(blueShirtId), PUBLIC);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).displayPrice()).isEqualByComparingTo(new BigDecimal("80"));
    }

    @Test
    @DisplayName("displayPrice — 활성 variant 없으면 basePrice 폴백 (redShoes: basePrice=200)")
    void findPublicProductSummariesByIds_displayPrice_basePriceFallback() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(redShoesId), PUBLIC);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).displayPrice()).isEqualByComparingTo(new BigDecimal("200"));
    }

    // =============================================================
    // purchasableVariantCount 집계
    // =============================================================

    @Test
    @DisplayName("purchasableVariantCount — 활성 AND stock>0 variant 수 (blueShirt: 1개)")
    void findPublicProductSummariesByIds_purchasableCount_activeAndStockPositive() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(blueShirtId), PUBLIC);

        assertThat(result.get(0).hasPurchasableVariant()).isTrue();
        // blueShirt: 활성 variant 중 재고>0은 SKU-SHIRT-90(재고5)만, SKU-SHIRT-80은 재고0 → 1개
        assertThat(result.get(0).purchasableVariantCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("purchasableVariantCount — variant 없음 → 0 (redShoes)")
    void findPublicProductSummariesByIds_purchasableCount_noVariant() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(redShoesId), PUBLIC);

        assertThat(result.get(0).hasPurchasableVariant()).isFalse();
        assertThat(result.get(0).purchasableVariantCount()).isEqualTo(0L);
    }

    // =============================================================
    // 드리프트 제거 — 화이트리스트 밖 status 항목 제거
    // =============================================================

    @Test
    @DisplayName("드리프트 제거 — HIDDEN/DRAFT id 포함 시 결과에서 제외됨")
    void findPublicProductSummariesByIds_driftRemoval_hiddenDraftExcluded() {
        List<Long> idsIncludingDrift = List.of(blueShirtId, hiddenItemId, draftItemId);

        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                idsIncludingDrift, PUBLIC);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).productId()).isEqualTo(blueShirtId);
    }

    @Test
    @DisplayName("드리프트 제거 — 모든 id가 화이트리스트 밖이면 빈 목록 반환")
    void findPublicProductSummariesByIds_allDrift_returnsEmpty() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(hiddenItemId, draftItemId), PUBLIC);

        assertThat(result).isEmpty();
    }

    // =============================================================
    // 빈 ids 목록
    // =============================================================

    @Test
    @DisplayName("빈 ids 목록 → 빈 결과 반환")
    void findPublicProductSummariesByIds_emptyIds_returnsEmpty() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(), PUBLIC);

        assertThat(result).isEmpty();
    }

    // =============================================================
    // SOLD_OUT 포함
    // =============================================================

    @Test
    @DisplayName("SOLD_OUT 상품 포함 — greenHat displayPrice/purchasable 집계")
    void findPublicProductSummariesByIds_soldOut_aggregatedCorrectly() {
        List<ProductSummaryProjection> result = productRepository.findPublicProductSummariesByIds(
                List.of(greenHatId), PUBLIC);

        assertThat(result).hasSize(1);
        ProductSummaryProjection proj = result.get(0);
        assertThat(proj.status()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(proj.displayPrice()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(proj.hasPurchasableVariant()).isTrue();
    }

    // =============================================================
    // 헬퍼
    // =============================================================

    private Category persistCategory(String name, String slug) {
        Category cat = Category.of(name, slug, null, 1);
        em.persist(cat);
        return cat;
    }

    private Product persistProduct(String name, String basePrice, ProductStatus status, Category category) {
        Product p = Product.create(ownerId, category, name, "설명", new BigDecimal(basePrice));
        if (status != ProductStatus.DRAFT) {
            p.update(category, name, "설명", new BigDecimal(basePrice), status);
        }
        em.persist(p);
        return p;
    }

    private ProductVariant persistVariant(Product product, String sku, String price, int stock, boolean active) {
        ProductVariant v = ProductVariant.create(product, sku, new BigDecimal(price), stock, active, java.util.Set.of());
        em.persist(v);
        return v;
    }

}
