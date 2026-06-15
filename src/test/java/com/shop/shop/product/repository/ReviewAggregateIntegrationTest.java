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

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리뷰 AVG/COUNT 집계 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>동일 product 다건 AVG/COUNT 정확성</li>
 *   <li>삭제 후 집계 갱신</li>
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
class ReviewAggregateIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("다건 리뷰 AVG/COUNT 정확성")
    void avgAndCount_multipleReviews_accurate() {
        long userId = insertUser("agg-1@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertDeliveredOrder(userId);
        long oi1 = insertOrderItem(orderId, variantId);
        long oi2 = insertOrderItem(orderId, variantId);
        long oi3 = insertOrderItem(orderId, variantId);

        reviewRepository.saveAndFlush(Review.create(productId, userId, oi1, 4, "좋아요"));
        reviewRepository.saveAndFlush(Review.create(productId, userId, oi2, 3, "보통"));
        reviewRepository.saveAndFlush(Review.create(productId, userId, oi3, 5, "최고"));

        Double avg = reviewRepository.avgRatingByProductId(productId);
        long count = reviewRepository.countByProductId(productId);

        assertThat(count).isEqualTo(3);
        // (4+3+5)/3 = 4.0
        assertThat(avg).isNotNull();
        assertThat(BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue())
                .isEqualTo(4.0);
    }

    @Test
    @DisplayName("삭제 후 집계 갱신")
    void afterDelete_aggregateUpdated() {
        long userId = insertUser("agg-2@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertDeliveredOrder(userId);
        long oi1 = insertOrderItem(orderId, variantId);
        long oi2 = insertOrderItem(orderId, variantId);

        Review r1 = reviewRepository.saveAndFlush(Review.create(productId, userId, oi1, 5, "최고"));
        reviewRepository.saveAndFlush(Review.create(productId, userId, oi2, 3, "보통"));

        // 삭제
        reviewRepository.deleteById(r1.getId());
        reviewRepository.flush();

        long count = reviewRepository.countByProductId(productId);
        Double avg = reviewRepository.avgRatingByProductId(productId);

        assertThat(count).isEqualTo(1);
        assertThat(BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue())
                .isEqualTo(3.0);
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
                Long.class, userId, categoryId, "집계 테스트 상품", 10000, "ON_SALE"
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
                Long.class, orderId, variantId, "집계 상품", 10000, 1, 10000
        );
    }
}
