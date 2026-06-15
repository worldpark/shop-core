package com.shop.shop.product.service;

import com.shop.shop.common.exception.ReviewNotPurchasedException;
import com.shop.shop.common.exception.ReviewTargetNotFoundException;
import com.shop.shop.common.exception.ReviewableProductMissingException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReviewService.create 구매 검증 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>검증:
 * <ul>
 *   <li>delivered 주문 → 리뷰 작성 성공</li>
 *   <li>비delivered 상태(pending) → ReviewNotPurchasedException(400)</li>
 *   <li>타 회원의 order_item → ReviewTargetNotFoundException(404)</li>
 *   <li>variantId null(삭제된 variant) → ReviewableProductMissingException(400)</li>
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
class ReviewPurchaseVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private JdbcTemplate jdbc;

    // =========================================================
    // 성공: delivered 상태 주문 → 리뷰 생성 가능
    // =========================================================

    @Test
    @DisplayName("delivered 주문 order_item → 리뷰 작성 성공")
    void create_deliveredOrderItem_success() {
        long userId = insertUser("pv-success@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(userId, "delivered");
        long orderItemId = insertOrderItem(orderId, variantId);

        // 예외 없이 정상 반환
        ReviewService.ReviewResult result = reviewService.create(userId, orderItemId, 4, "좋아요");

        org.assertj.core.api.Assertions.assertThat(result.reviewId()).isPositive();
        org.assertj.core.api.Assertions.assertThat(result.productId()).isEqualTo(productId);
    }

    // =========================================================
    // 실패: 비delivered 상태 → 400
    // =========================================================

    @Test
    @DisplayName("pending 상태 주문 order_item → ReviewNotPurchasedException(400)")
    void create_pendingOrderItem_throwsNotPurchased() {
        long userId = insertUser("pv-pending@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(userId, "pending");
        long orderItemId = insertOrderItem(orderId, variantId);

        assertThatThrownBy(() -> reviewService.create(userId, orderItemId, 4, "구매안함"))
                .isInstanceOf(ReviewNotPurchasedException.class);
    }

    @Test
    @DisplayName("paid 상태 주문 order_item → ReviewNotPurchasedException(400)")
    void create_paidOrderItem_throwsNotPurchased() {
        long userId = insertUser("pv-paid@test.com");
        long productId = insertProduct(userId);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(userId, "paid");
        long orderItemId = insertOrderItem(orderId, variantId);

        assertThatThrownBy(() -> reviewService.create(userId, orderItemId, 5, "배송전"))
                .isInstanceOf(ReviewNotPurchasedException.class);
    }

    // =========================================================
    // 실패: 타 회원의 order_item → 404 (IDOR 은닉)
    // =========================================================

    @Test
    @DisplayName("타 회원의 order_item → ReviewTargetNotFoundException(404)")
    void create_otherMembersOrderItem_throwsTargetNotFound() {
        long owner = insertUser("pv-owner@test.com");
        long attacker = insertUser("pv-attacker@test.com");
        long productId = insertProduct(owner);
        long variantId = insertVariant(productId);
        long orderId = insertOrder(owner, "delivered");
        long orderItemId = insertOrderItem(orderId, variantId);

        // attacker가 owner의 order_item으로 리뷰 시도
        assertThatThrownBy(() -> reviewService.create(attacker, orderItemId, 5, "탈취 시도"))
                .isInstanceOf(ReviewTargetNotFoundException.class);
    }

    // =========================================================
    // 실패: variantId null (variant 삭제됨) → productId=null → 400
    // =========================================================

    @Test
    @DisplayName("variant 없는 order_item(variant_id null) → ReviewableProductMissingException(400)")
    void create_nullVariantId_throwsProductMissing() {
        long userId = insertUser("pv-nullvar@test.com");
        long orderId = insertOrder(userId, "delivered");
        // order_item.variant_id = null (variant 삭제 시나리오)
        long orderItemId = insertOrderItemNullVariant(orderId);

        assertThatThrownBy(() -> reviewService.create(userId, orderItemId, 3, "상품 삭제됨"))
                .isInstanceOf(ReviewableProductMissingException.class);
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

    private long insertOrder(long userId, String status) {
        return jdbc.queryForObject(
                "INSERT INTO orders(user_id, order_number, status, items_amount, discount_amount, shipping_fee, final_amount) "
                + "VALUES(?,?,?,?,?,?,?) RETURNING id",
                Long.class, userId, "ORD-" + System.nanoTime(), status, 10000, 0, 0, 10000
        );
    }

    private long insertOrderItem(long orderId, long variantId) {
        return jdbc.queryForObject(
                "INSERT INTO order_items(order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES(?,?,?,?,?,?) RETURNING id",
                Long.class, orderId, variantId, "검증 상품", 10000, 1, 10000
        );
    }

    private long insertOrderItemNullVariant(long orderId) {
        return jdbc.queryForObject(
                "INSERT INTO order_items(order_id, variant_id, product_name, unit_price, quantity, line_amount) "
                + "VALUES(?,null,?,?,?,?) RETURNING id",
                Long.class, orderId, "삭제된 상품", 10000, 1, 10000
        );
    }
}
