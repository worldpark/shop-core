package com.shop.shop.product.service;

import com.shop.shop.common.crypto.CryptoConfig;
import com.shop.shop.common.crypto.EnvelopeEncryptionService;
import com.shop.shop.product.domain.Category;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카테고리 삭제 시 DB ON DELETE SET NULL 동작 검증 통합 테스트 (실 PostgreSQL).
 *
 * <p>검증 목표:
 * <ul>
 *   <li>상품(products.category_id) → 카테고리 삭제 시 category_id = NULL(미분류 전환)</li>
 *   <li>자식 카테고리(categories.parent_id) → 부모 삭제 시 parent_id = NULL(root 승격)</li>
 * </ul>
 *
 * <p>FK ON DELETE SET NULL은 JPA cascade/orphanRemoval 없이 DB가 처리한다.
 * H2로는 PostgreSQL 특성이 재현되지 않으므로 Testcontainers로 실 PostgreSQL을 사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CategoryService.class, CryptoConfig.class, EnvelopeEncryptionService.class})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class CategoryDeleteIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("카테고리 삭제 시 매달린 상품 category_id=NULL, 자식 카테고리 parent_id=NULL")
    void deleteCategory_sets_null_on_products_and_children() {
        // given: 판매자 계정 (users FK 충족)
        em.getEntityManager().createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role) "
                        + "VALUES ('seller@test.com', 'x', 'Seller', 'SELLER')").executeUpdate();
        long sellerId = ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = 'seller@test.com'")
                .getSingleResult()).longValue();

        // 부모 카테고리
        Category parent = Category.of("전자", "electronics", null, 0);
        parent = categoryRepository.save(parent);
        long parentId = parent.getId();

        // 자식 카테고리 (parent_id → 부모)
        Category child = Category.of("스마트폰", "smartphones", parent, 1);
        child = categoryRepository.save(child);
        long childId = child.getId();

        // 상품 (category_id → 부모)
        Product product = Product.create(sellerId, parent, "갤럭시", "설명", BigDecimal.valueOf(1000));
        product = productRepository.save(product);
        long productId = product.getId();

        em.flush();
        em.clear();

        // when: 부모 카테고리 삭제
        categoryService.deleteCategory(parentId);
        em.flush();
        em.clear();

        // then: 부모 카테고리 삭제됨
        Optional<Category> deletedParent = categoryRepository.findById(parentId);
        assertThat(deletedParent).isEmpty();

        // then: 상품 category_id = NULL (미분류 전환)
        Object productCategoryId = em.getEntityManager()
                .createNativeQuery("SELECT category_id FROM products WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult();
        assertThat(productCategoryId).as("상품 category_id는 NULL(미분류 전환)이어야 한다").isNull();

        // then: 자식 카테고리 parent_id = NULL (root 승격)
        Object childParentId = em.getEntityManager()
                .createNativeQuery("SELECT parent_id FROM categories WHERE id = :id")
                .setParameter("id", childId)
                .getSingleResult();
        assertThat(childParentId).as("자식 카테고리 parent_id는 NULL(root 승격)이어야 한다").isNull();
    }
}
