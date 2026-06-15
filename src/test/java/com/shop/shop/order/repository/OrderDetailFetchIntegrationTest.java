package com.shop.shop.order.repository;

import com.shop.shop.order.domain.Order;
import com.shop.shop.order.domain.OrderItem;
import com.shop.shop.order.domain.OrderItemOptionValue;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 주문 상세 조회 fetch 정합 통합 테스트 (실 PostgreSQL, Testcontainers).
 *
 * <p>회귀 가드: {@code findWithItemsByIdAndUserId}가 items·optionValues 두 bag을 동시에
 * fetch join하면 {@code MultipleBagFetchException}이 발생한다. 본 테스트는 items+optionValues를
 * 모두 가진 주문을 저장한 뒤 조회·접근해 예외 없이 로딩되는지 검증한다.
 * (수정 전 EntityGraph가 {"items","items.optionValues"}였을 때 이 테스트는 RED.)
 *
 * <p>단위(Mockito) 테스트는 리포지토리를 목하므로 실쿼리의 MultipleBagFetch를 잡지 못한다 — 통합 필수.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OrderDetailFetchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("findWithItemsByIdAndUserId — items+optionValues 동시 보유 주문도 MultipleBagFetchException 없이 로딩된다")
    void loadOrderWithItemsAndOptionValues() {
        long userId = insertUser("order-fetch-" + System.nanoTime() + "@test.com");
        long productId = insertProduct(userId);
        long variantId1 = insertVariant(productId);
        long variantId2 = insertVariant(productId);

        Order order = Order.create(
                userId, "ORD-FETCH-" + System.nanoTime(), new BigDecimal("30000"), BigDecimal.ZERO,
                "수령인", "010-0000-0000", "12345", "서울시 강남구", "101호");

        OrderItem item1 = OrderItem.create(variantId1, "상품A", "색상:빨강 / 사이즈:L", new BigDecimal("10000"), 1);
        item1.addOptionValue(OrderItemOptionValue.create("색상", "빨강", 0));
        item1.addOptionValue(OrderItemOptionValue.create("사이즈", "L", 1));

        OrderItem item2 = OrderItem.create(variantId2, "상품B", "색상:파랑", new BigDecimal("20000"), 1);
        item2.addOptionValue(OrderItemOptionValue.create("색상", "파랑", 0));

        order.addItem(item1);
        order.addItem(item2);

        Order saved = orderRepository.save(order);
        em.flush();
        em.clear(); // 영속성 컨텍스트 비워 조회가 실제 fetch 경로를 타게 함

        Order[] found = new Order[1];
        // 1) 두 bag 동시 fetch 회피 — 조회 자체가 MultipleBagFetchException 없이 성공해야 함
        assertThatCode(() ->
                found[0] = orderRepository.findWithItemsByIdAndUserId(saved.getId(), userId).orElseThrow()
        ).doesNotThrowAnyException();

        // 2) items 즉시 로딩
        assertThat(found[0].getItems()).hasSize(2);

        // 3) optionValues 접근(@BatchSize 배치 로딩) — 트랜잭션 내라 LazyInit 없이 정상
        int totalOptions = found[0].getItems().stream()
                .mapToInt(i -> i.getOptionValues().size())
                .sum();
        assertThat(totalOptions).isEqualTo(3);
    }

    /** orders.user_id FK 충족을 위한 최소 user 시드. (기존 통합테스트 insertUser 컬럼 동일) */
    private long insertUser(String email) {
        em.getEntityManager()
                .createNativeQuery("INSERT INTO users(email, password_hash, name, role, status) VALUES(?,?,?,?,?)")
                .setParameter(1, email)
                .setParameter(2, "hash")
                .setParameter(3, "테스터")
                .setParameter(4, "CONSUMER")
                .setParameter(5, "ACTIVE")
                .executeUpdate();
        Number id = (Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = ?")
                .setParameter(1, email)
                .getSingleResult();
        return id.longValue();
    }

    private long insertProduct(long userId) {
        long categoryId = insertCategory();
        String name = "주문fetch상품-" + System.nanoTime();
        em.getEntityManager()
                .createNativeQuery("INSERT INTO products(owner_id, category_id, name, base_price, status) VALUES(?,?,?,?,?)")
                .setParameter(1, userId).setParameter(2, categoryId).setParameter(3, name)
                .setParameter(4, 10000).setParameter(5, "ON_SALE")
                .executeUpdate();
        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM products WHERE name = ?")
                .setParameter(1, name).getSingleResult()).longValue();
    }

    private long insertCategory() {
        long nano = System.nanoTime();
        String slug = "cat-" + nano;
        em.getEntityManager()
                .createNativeQuery("INSERT INTO categories(name, slug, sort_order) VALUES(?,?,?)")
                .setParameter(1, "카테고리" + nano).setParameter(2, slug).setParameter(3, 1)
                .executeUpdate();
        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM categories WHERE slug = ?")
                .setParameter(1, slug).getSingleResult()).longValue();
    }

    private long insertVariant(long productId) {
        String sku = "SKU-" + System.nanoTime();
        em.getEntityManager()
                .createNativeQuery("INSERT INTO product_variants(product_id, sku, price, stock) VALUES(?,?,?,?)")
                .setParameter(1, productId).setParameter(2, sku).setParameter(3, 10000).setParameter(4, 100)
                .executeUpdate();
        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM product_variants WHERE sku = ?")
                .setParameter(1, sku).getSingleResult()).longValue();
    }
}
