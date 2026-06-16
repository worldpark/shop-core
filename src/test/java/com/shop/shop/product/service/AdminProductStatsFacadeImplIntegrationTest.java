package com.shop.shop.product.service;

import com.shop.shop.product.spi.AdminProductStatsFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminProductStatsFacade} 통합 테스트 (실 PostgreSQL).
 *
 * <p>Task 043 §1.3 — 관리자 통계 대시보드 상품 통계 검증.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>countPublishedProducts — DRAFT/HIDDEN 제외, ON_SALE/SOLD_OUT만 집계</li>
 *   <li>countPublishedProductsWithSales — 빈 set=0 가드, 미게시 상품(DRAFT/HIDDEN) 제외, DISTINCT</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false",
        "shop.order.pending-expiry.enabled=false"
})
class AdminProductStatsFacadeImplIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private AdminProductStatsFacade adminProductStatsFacade;

    @Autowired
    private JdbcTemplate jdbc;

    // ============================================================
    // countPublishedProducts
    // ============================================================

    @Test
    @DisplayName("countPublishedProducts — ON_SALE/SOLD_OUT만 집계, DRAFT/HIDDEN 제외")
    void countPublishedProducts_excludes_draft_and_hidden() {
        long before = adminProductStatsFacade.countPublishedProducts();

        long ownerId = insertUser("prod-stats@test.com");

        // 게시 상품 (집계 대상)
        insertProduct("게시상품-ON_SALE", "ON_SALE", ownerId);
        insertProduct("게시상품-SOLD_OUT", "SOLD_OUT", ownerId);

        // 미게시 상품 (집계 제외)
        insertProduct("미게시-DRAFT", "DRAFT", ownerId);
        insertProduct("미게시-HIDDEN", "HIDDEN", ownerId);

        long after = adminProductStatsFacade.countPublishedProducts();

        // ON_SALE + SOLD_OUT 2건만 증가
        assertThat(after).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("countPublishedProducts — DRAFT 상품만 있으면 0 (기준 상태 없음 기준)")
    void countPublishedProducts_returns_increment_only_for_published() {
        long before = adminProductStatsFacade.countPublishedProducts();

        long ownerId = insertUser("draft-only@test.com");

        // DRAFT만
        insertProduct("DRAFT전용상품", "DRAFT", ownerId);

        long after = adminProductStatsFacade.countPublishedProducts();

        assertThat(after).isEqualTo(before); // 변화 없음
    }

    // ============================================================
    // countPublishedProductsWithSales — 빈 set 가드
    // ============================================================

    @Test
    @DisplayName("countPublishedProductsWithSales — 빈 컬렉션 → 0 즉시 반환 (DB 조회 없음)")
    void countPublishedProductsWithSales_empty_collection_returns_zero() {
        long result = adminProductStatsFacade.countPublishedProductsWithSales(List.of());

        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("countPublishedProductsWithSales — null 컬렉션 → 0 즉시 반환")
    void countPublishedProductsWithSales_null_collection_returns_zero() {
        long result = adminProductStatsFacade.countPublishedProductsWithSales(null);

        assertThat(result).isEqualTo(0L);
    }

    // ============================================================
    // countPublishedProductsWithSales — 미게시 상품 제외
    // ============================================================

    @Test
    @DisplayName("countPublishedProductsWithSales — 게시 상품(ON_SALE/SOLD_OUT) variant만 집계, DRAFT/HIDDEN 제외")
    void countPublishedProductsWithSales_excludes_unpublished_products() {
        long ownerId = insertUser("sales-excl@test.com");

        // 게시 상품 + variant
        long publishedProductId = insertProduct("게시판매상품", "ON_SALE", ownerId);
        long publishedVariantId = insertVariant(publishedProductId, "PUB-SKU-" + System.nanoTime());

        // 미게시 상품 + variant
        long draftProductId = insertProduct("DRAFT판매안됨", "DRAFT", ownerId);
        long draftVariantId = insertVariant(draftProductId, "DRAFT-SKU-" + System.nanoTime());

        long hiddenProductId = insertProduct("HIDDEN판매안됨", "HIDDEN", ownerId);
        long hiddenVariantId = insertVariant(hiddenProductId, "HIDDEN-SKU-" + System.nanoTime());

        // 게시 variant만 → 1개 게시 상품
        long countPublishedOnly = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(publishedVariantId));
        assertThat(countPublishedOnly).isEqualTo(1L);

        // DRAFT variant만 → 0 (미게시 상품)
        long countDraftOnly = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(draftVariantId));
        assertThat(countDraftOnly).isEqualTo(0L);

        // HIDDEN variant만 → 0 (미게시 상품)
        long countHiddenOnly = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(hiddenVariantId));
        assertThat(countHiddenOnly).isEqualTo(0L);

        // 섞어서 → 게시 1건만
        long countMixed = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(publishedVariantId, draftVariantId, hiddenVariantId));
        assertThat(countMixed).isEqualTo(1L);
    }

    @Test
    @DisplayName("countPublishedProductsWithSales — 동일 상품의 여러 variant → DISTINCT 1건")
    void countPublishedProductsWithSales_distinct_products() {
        long ownerId = insertUser("distinct-prod@test.com");

        long productId = insertProduct("DISTINCT게시상품", "ON_SALE", ownerId);
        long variantId1 = insertVariant(productId, "DIST-SKU1-" + System.nanoTime());
        long variantId2 = insertVariant(productId, "DIST-SKU2-" + System.nanoTime());

        // 동일 상품의 variant 2개 → DISTINCT 1건
        long count = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(variantId1, variantId2));

        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("countPublishedProductsWithSales — 다른 상품의 각 variant → 각각 1건씩 집계")
    void countPublishedProductsWithSales_multiple_distinct_products() {
        long ownerId = insertUser("multi-prod@test.com");

        long productA = insertProduct("상품A", "ON_SALE", ownerId);
        long variantA = insertVariant(productA, "MULTI-A-SKU-" + System.nanoTime());

        long productB = insertProduct("상품B", "SOLD_OUT", ownerId);
        long variantB = insertVariant(productB, "MULTI-B-SKU-" + System.nanoTime());

        long count = adminProductStatsFacade.countPublishedProductsWithSales(
                List.of(variantA, variantB));

        assertThat(count).isEqualTo(2L);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, password_hash, name, role) "
                + "VALUES (?, 'x', '테스트', 'SELLER')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertProduct(String name, String status, long ownerId) {
        jdbc.update("INSERT INTO products (owner_id, name, description, base_price, status) "
                + "VALUES (?, ?, '설명', 10000, ?)", ownerId, name, status);
        return jdbc.queryForObject(
                "SELECT id FROM products WHERE name=? AND owner_id=? ORDER BY id DESC LIMIT 1",
                Long.class, name, ownerId);
    }

    private long insertVariant(long productId, String sku) {
        jdbc.update("INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                + "VALUES (?, ?, 10000, 10, true)", productId, sku);
        return jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE sku=?", Long.class, sku);
    }
}
