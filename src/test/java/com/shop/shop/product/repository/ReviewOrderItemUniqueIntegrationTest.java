package com.shop.shop.product.repository;

import com.shop.shop.product.domain.Review;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * order_item_id UNIQUE 제약 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>같은 order_item_id 2회 INSERT → 2번째 DataIntegrityViolation(uq_reviews_order_item_id)</li>
 *   <li>삭제 후 재작성 성공 (UNIQUE 해제)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(FakeRefreshTokenStore.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.modulith.events.externalization.enabled=false"
})
class ReviewOrderItemUniqueIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 order_item_id 2회 INSERT → 2번째 DataIntegrityViolation")
    void duplicateOrderItemId_throwsDataIntegrityViolation() {
        // given — 사전 데이터 준비 (users, products, variants, orders, order_items)
        long userId = insertUser("review-unique-1@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(userId);
        long orderItemId = insertOrderItem(orderId, variantId);

        Review first = Review.create(productId, userId, orderItemId, 4, "첫 리뷰");
        reviewRepository.saveAndFlush(first);

        // when/then
        Review second = Review.create(productId, userId, orderItemId, 5, "중복 리뷰");
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("삭제 후 재작성 성공 (UNIQUE 해제)")
    void deleteAndRecreate_success() {
        long userId = insertUser("review-unique-2@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(userId);
        long orderItemId = insertOrderItem(orderId, variantId);

        // 최초 작성
        Review first = Review.create(productId, userId, orderItemId, 3, "처음");
        reviewRepository.saveAndFlush(first);

        // 삭제
        reviewRepository.deleteById(first.getId());
        reviewRepository.flush();

        // 재작성 성공
        Review second = Review.create(productId, userId, orderItemId, 5, "재작성");
        Review saved = reviewRepository.saveAndFlush(second);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRating()).isEqualTo(5);
    }

    // =========================================================
    // 헬퍼 — 최소한의 DB 데이터 삽입
    // =========================================================

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?) RETURNING id",
                Long.class, email, "hash", "테스터", "CONSUMER", "ACTIVE"
        );
    }

    private long insertProduct(long userId) {
        long categoryId = insertCategory();
        return jdbc.queryForObject(
                "INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?) RETURNING id",
                Long.class, userId, categoryId, "테스트 상품", 10000, "ON_SALE"
        );
    }

    private long insertCategory() {
        long nano = System.nanoTime();
        return jdbc.queryForObject(
                "INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?) RETURNING id",
                Long.class, "카테고리" + nano, "cat-" + nano, 1
        );
    }

    private long insertVariant(long productId) {
        return jdbc.queryForObject(
                "INSERT INTO product_variants(product_id, sku, price, stock) VALUES(?,?,?,?) RETURNING id",
                Long.class, productId, "SKU-" + System.nanoTime(), 10000, 100
        );
    }

    private long insertOrder(long userId) {
        return jdbc.queryForObject(
                "INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount) " +
                "VALUES(?,?,?,?,?,?,?) RETURNING id",
                Long.class, userId, "ORD-" + System.nanoTime(), "delivered", 10000, 0, 0, 10000
        );
    }

    private long insertOrderItem(long orderId, long variantId) {
        return jdbc.queryForObject(
                "INSERT INTO order_items(order_id, variant_id, product_name, unit_price, quantity, line_amount) " +
                "VALUES(?,?,?,?,?,?) RETURNING id",
                Long.class, orderId, variantId, "테스트 상품", 10000, 1, 10000
        );
    }
}
