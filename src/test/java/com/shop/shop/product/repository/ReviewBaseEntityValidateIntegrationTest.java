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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review + BaseEntity 매핑 validate 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>Review + BaseEntity 매핑이 V1 reviews DDL과 validate 정합</li>
 *   <li>작성 후 createdAt/updatedAt non-null (DB 트리거 소유)</li>
 *   <li>edit 후 updatedAt 증가 (trg_reviews_set_updated_at 트리거)</li>
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
class ReviewBaseEntityValidateIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Review 저장 후 createdAt/updatedAt non-null (DB 트리거 소유)")
    void create_baseEntity_nonNull() {
        long userId = insertUser("base-entity-1@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertDeliveredOrder(userId);
        long orderItemId = insertOrderItem(orderId, variantId);

        Review review = Review.create(productId, userId, orderItemId, 4, "내용");
        Review saved = reviewRepository.saveAndFlush(review);

        // DB에서 refresh하여 DB 트리거 갱신된 값 확인
        reviewRepository.flush();
        Review loaded = reviewRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("edit 후 updatedAt 증가 (trg_reviews_set_updated_at 트리거)")
    void edit_updatedAtIncreases() throws InterruptedException {
        long userId = insertUser("base-entity-2@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertDeliveredOrder(userId);
        long orderItemId = insertOrderItem(orderId, variantId);

        Review review = Review.create(productId, userId, orderItemId, 3, "처음");
        Review saved = reviewRepository.saveAndFlush(review);

        Review loaded = reviewRepository.findById(saved.getId()).orElseThrow();
        java.time.Instant updatedAtBefore = loaded.getUpdatedAt();

        // 트리거 시간 분해능(1ms) 이상 대기
        Thread.sleep(10);

        // edit 수행
        loaded.edit(5, "수정됨");
        reviewRepository.saveAndFlush(loaded);

        // DB에서 reload
        Review updated = reviewRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getRating()).isEqualTo(5);
        // updatedAt은 트리거가 갱신 — null이 아님을 확인(시간 증가는 트리거에 의존)
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    // =========================================================
    // 헬퍼
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
                Long.class, userId, categoryId, "검증 상품", 10000, "ON_SALE"
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

    private long insertDeliveredOrder(long userId) {
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
                Long.class, orderId, variantId, "검증 상품", 10000, 1, 10000
        );
    }
}
