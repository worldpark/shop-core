package com.shop.shop.product.repository;

import com.shop.shop.common.crypto.CryptoConfig;
import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.ProductSearchSnapshotProjection;
import com.shop.shop.product.dto.ProductSummaryProjection;
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

import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductRepository#findSearchSnapshot} 대 {@link ProductRepository#findPublicProductsLatest}
 * displayPrice/purchasableVariantCount 패리티 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검색 색인 문서의 displayPrice·purchasableVariantCount 산출식이
 * 공개 목록 집계({@code findPublicProductsLatest})와 동일한 결과를 반환함을 실 DB로 증명한다.
 * 쿼리 코드는 수정하지 않는다 — 이 테스트는 기존 쿼리 정합을 영속적으로 보장한다.
 *
 * <p>케이스:
 * <ol>
 *   <li>활성 variant 다수 — displayPrice = MIN(variant.price), purchasable = 활성+stock>0 개수</li>
 *   <li>활성 variant 없음 — displayPrice = basePrice, purchasable = 0</li>
 *   <li>활성이지만 stock=0 — purchasable 산정 제외</li>
 * </ol>
 *
 * <p>{@link ProductRepositoryIntegrationTest}와 동일한 슬라이스 구성 ({@code @DataJpaTest} +
 * {@code @ServiceConnection PostgreSQLContainer}).
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
class ProductSearchSnapshotParityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    /** 공개 노출 허용 status 화이트리스트. */
    private static final List<ProductStatus> PUBLIC = List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager em;

    /** FK 충족용 owner_id — @BeforeEach에서 native insert 후 채움 */
    private long ownerId;

    @BeforeEach
    void setUp() {
        // owner FK(users.id) 충족용 사용자 1명 — product 모듈에 User Entity 없어 native insert 사용
        // (ProductRepositoryIntegrationTest 동형 패턴)
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('parity-test@test.com', 'x', 'Tester', 'SELLER')").executeUpdate();
        ownerId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'parity-test@test.com'")
                .getSingleResult()).longValue();
    }

    // ============================================================
    // 케이스 1: 활성 variant 다수 — displayPrice = MIN price, purchasable = stock>0 개수
    // ============================================================

    @Test
    @DisplayName("케이스1: 활성 variant 다수 — findSearchSnapshot.displayPrice = MIN(활성 price), purchasable = stock>0 개수")
    void multipleActiveVariants_displayPriceIsMinPrice() {
        // given: ON_SALE 상품, 활성 variant 3개 (price 다름, stock 일부 0)
        Product product = onSaleProduct("노트북", new BigDecimal("1500000"));

        // variant1: active=true, price=900000, stock=5 → purchasable 포함
        em.persist(ProductVariant.create(product, "SKU-MULTI-A", new BigDecimal("900000"), 5, true, Set.of()));
        // variant2: active=true, price=1200000, stock=3 → purchasable 포함
        em.persist(ProductVariant.create(product, "SKU-MULTI-B", new BigDecimal("1200000"), 3, true, Set.of()));
        // variant3: active=true, price=800000, stock=0 → purchasable 제외(stock=0), 하지만 price는 MIN 후보
        em.persist(ProductVariant.create(product, "SKU-MULTI-C", new BigDecimal("800000"), 0, true, Set.of()));

        em.flush();
        em.clear();

        long productId = product.getId();

        // when — snapshot
        Optional<ProductSearchSnapshotProjection> snapshotOpt = productRepository.findSearchSnapshot(productId);
        // when — public list
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 10));

        // then — snapshot present
        assertThat(snapshotOpt).isPresent();
        ProductSearchSnapshotProjection snapshot = snapshotOpt.get();

        // find matching public summary
        ProductSummaryProjection summary = page.getContent().stream()
                .filter(p -> p.productId() == productId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("상품이 공개 목록에 없음: " + productId));

        // displayPrice parity: 둘 다 MIN(활성 variant price) = 800000
        assertThat(snapshot.displayPrice())
                .as("snapshot.displayPrice = MIN 활성 variant price")
                .isEqualByComparingTo(new BigDecimal("800000"));
        assertThat(snapshot.displayPrice())
                .as("findSearchSnapshot.displayPrice == findPublicProductsLatest.displayPrice")
                .isEqualByComparingTo(summary.displayPrice());

        // purchasable parity: 활성+stock>0 = SKU-MULTI-A + SKU-MULTI-B = 2 (SKU-MULTI-C stock=0 제외)
        assertThat(snapshot.purchasableVariantCount())
                .as("snapshot.purchasableVariantCount = stock>0 활성 variant 수")
                .isEqualTo(2L);
        assertThat(snapshot.purchasableVariantCount())
                .as("findSearchSnapshot.purchasableVariantCount == findPublicProductsLatest.purchasableVariantCount")
                .isEqualTo(summary.purchasableVariantCount());
    }

    // ============================================================
    // 케이스 2: 활성 variant 없음 — displayPrice = basePrice, purchasable = 0
    // ============================================================

    @Test
    @DisplayName("케이스2: 활성 variant 없음 — findSearchSnapshot.displayPrice = basePrice, purchasable = 0")
    void noActiveVariants_displayPriceIsBasePrice() {
        // given: ON_SALE 상품, 비활성 variant 1개(active=false → LEFT JOIN 조인 안 됨)
        Product product = onSaleProduct("텀블러", new BigDecimal("25000"));

        // variant: active=false → v.isActive=true 조건으로 JOIN 제외
        em.persist(ProductVariant.create(product, "SKU-INACTIVE", new BigDecimal("20000"), 10, false, Set.of()));

        em.flush();
        em.clear();

        long productId = product.getId();

        // when
        Optional<ProductSearchSnapshotProjection> snapshotOpt = productRepository.findSearchSnapshot(productId);
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 10));

        // then
        assertThat(snapshotOpt).isPresent();
        ProductSearchSnapshotProjection snapshot = snapshotOpt.get();

        ProductSummaryProjection summary = page.getContent().stream()
                .filter(p -> p.productId() == productId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("상품이 공개 목록에 없음: " + productId));

        // displayPrice = basePrice (활성 variant 없음 → COALESCE(null, basePrice) = basePrice)
        assertThat(snapshot.displayPrice())
                .as("snapshot.displayPrice = basePrice when no active variant")
                .isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(snapshot.displayPrice())
                .as("parity with public list")
                .isEqualByComparingTo(summary.displayPrice());

        // purchasable = 0
        assertThat(snapshot.purchasableVariantCount())
                .as("purchasable = 0 when no active variant")
                .isZero();
        assertThat(snapshot.purchasableVariantCount())
                .as("parity with public list")
                .isEqualTo(summary.purchasableVariantCount());
    }

    // ============================================================
    // 케이스 3: 활성이지만 stock=0 — purchasable 제외
    // ============================================================

    @Test
    @DisplayName("케이스3: 활성 variant stock=0 — purchasable 산정 제외 (displayPrice는 해당 가격 반영)")
    void activeVariantStockZero_excludedFromPurchasable() {
        // given: ON_SALE 상품, 활성 variant 1개 but stock=0
        Product product = onSaleProduct("무선이어폰", new BigDecimal("80000"));

        em.persist(ProductVariant.create(product, "SKU-ZERO", new BigDecimal("75000"), 0, true, Set.of()));

        em.flush();
        em.clear();

        long productId = product.getId();

        // when
        Optional<ProductSearchSnapshotProjection> snapshotOpt = productRepository.findSearchSnapshot(productId);
        Page<ProductSummaryProjection> page = productRepository.findPublicProductsLatest(
                PUBLIC, null, null, PageRequest.of(0, 10));

        // then
        assertThat(snapshotOpt).isPresent();
        ProductSearchSnapshotProjection snapshot = snapshotOpt.get();

        ProductSummaryProjection summary = page.getContent().stream()
                .filter(p -> p.productId() == productId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("상품이 공개 목록에 없음: " + productId));

        // displayPrice = 75000 (활성 variant의 price가 반영 — COALESCE(MIN(75000), basePrice))
        assertThat(snapshot.displayPrice())
                .as("displayPrice = active variant price even when stock=0")
                .isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(snapshot.displayPrice())
                .as("parity with public list")
                .isEqualByComparingTo(summary.displayPrice());

        // purchasable = 0 (stock=0이므로 제외)
        assertThat(snapshot.purchasableVariantCount())
                .as("purchasable = 0 when active variant has stock=0")
                .isZero();
        assertThat(snapshot.purchasableVariantCount())
                .as("parity with public list")
                .isEqualTo(summary.purchasableVariantCount());
    }

    // ============================================================
    // helpers
    // ============================================================

    /**
     * ON_SALE 상품을 생성·영속화한 뒤 반환한다.
     * Product.create()는 status=DRAFT 강제이므로 update()로 ON_SALE 전이한다.
     */
    private Product onSaleProduct(String name, BigDecimal basePrice) {
        Product product = Product.create(ownerId, null, name, null, basePrice);
        em.persist(product);
        product.update(null, name, null, basePrice, ProductStatus.ON_SALE);
        return product;
    }
}
