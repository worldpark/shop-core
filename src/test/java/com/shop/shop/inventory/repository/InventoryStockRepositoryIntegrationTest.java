package com.shop.shop.inventory.repository;

import com.shop.shop.inventory.domain.VariantStock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InventoryStockRepository} 통합 테스트 (실 PostgreSQL).
 *
 * <p>검증:
 * <ul>
 *   <li>findByIdForUpdate: SELECT ... FOR UPDATE 발행</li>
 *   <li>VariantStock validate 매핑 — product_variants 기존 컬럼(id/stock/is_active)만 매핑</li>
 *   <li>stock 차감 반영 (dirty checking)</li>
 *   <li>is_active 읽기</li>
 * </ul>
 *
 * <p>테스트 프로파일 구성: {@code spring.autoconfigure.exclude=}로 기본 제외 리셋 + Flyway 활성화.
 * {@code ddl-auto=validate}: VariantStock이 product_variants 기존 컬럼만 매핑하므로 validate 통과.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class InventoryStockRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private InventoryStockRepository inventoryStockRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("findByIdForUpdate: 존재하는 variant → VariantStock 반환(stock/isActive 읽기)")
    void findByIdForUpdate_existingVariant_returnsVariantStock() {
        // given: 사용자·카테고리·상품·variant 삽입
        long variantId = insertVariantWithStock(5, true);
        em.flush();
        em.clear();

        // when
        Optional<VariantStock> result = inventoryStockRepository.findByIdForUpdate(variantId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getStock()).isEqualTo(5);
        assertThat(result.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("findByIdForUpdate: 미존재 variantId → empty 반환")
    void findByIdForUpdate_nonExistentVariant_returnsEmpty() {
        Optional<VariantStock> result = inventoryStockRepository.findByIdForUpdate(999_999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("VariantStock.decrease: stock 차감이 DB에 반영됨 (dirty checking)")
    void variantStock_decrease_reflectsInDb() {
        long variantId = insertVariantWithStock(10, true);
        em.flush();
        em.clear();

        // when: 트랜잭션 안에서 decrease
        VariantStock vs = inventoryStockRepository.findByIdForUpdate(variantId).orElseThrow();
        vs.decrease(3);
        em.flush();
        em.clear();

        // then: DB에서 재조회
        VariantStock reloaded = inventoryStockRepository.findById(variantId).orElseThrow();
        assertThat(reloaded.getStock()).isEqualTo(7);
    }

    @Test
    @DisplayName("비활성(is_active=false) variant → isActive=false 읽기")
    void findByIdForUpdate_inactiveVariant_isActiveFalse() {
        long variantId = insertVariantWithStock(5, false);
        em.flush();
        em.clear();

        VariantStock vs = inventoryStockRepository.findByIdForUpdate(variantId).orElseThrow();

        assertThat(vs.isActive()).isFalse();
    }

    /**
     * 테스트용 사용자·카테고리·상품·variant native insert 후 variantId 반환.
     *
     * <p>FK 제약 충족: products.category_id(nullable), product_variants.product_id.
     */
    private long insertVariantWithStock(int stock, boolean isActive) {
        // 상품 insert (category_id nullable)
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO products (name, description, base_price, status) "
                        + "VALUES ('테스트상품', '설명', 1000, 'ON_SALE')")
                .executeUpdate();
        long productId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM products ORDER BY id DESC LIMIT 1")
                .getSingleResult()).longValue();

        // variant insert
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO product_variants (product_id, sku, price, stock, is_active) "
                        + "VALUES (?1, ?2, 1000, ?3, ?4)")
                .setParameter(1, productId)
                .setParameter(2, "SKU-" + System.nanoTime())
                .setParameter(3, stock)
                .setParameter(4, isActive)
                .executeUpdate();

        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM product_variants ORDER BY id DESC LIMIT 1")
                .getSingleResult()).longValue();
    }
}
