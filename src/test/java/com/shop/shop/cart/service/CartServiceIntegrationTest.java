package com.shop.shop.cart.service;

import com.shop.shop.cart.domain.Cart;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.product.spi.ProductPurchaseCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link CartService} 빈 장바구니 조회 통합 테스트 (실 PostgreSQL).
 *
 * <p>회귀: 영속된 cart가 없는 사용자가 {@code getCart}를 호출하면, 과거에는 미영속 Cart(id=null)를
 * {@code findByCartId(long)}에 넘겨 NPE가 났다. 실제 DB(Testcontainers)에서 cart 부재/빈 항목 경로가
 * 예외 없이 빈 CartView를 반환하는지 검증한다. 단위 테스트(Mockito)와 달리 실제 persistence 경계를 통과시킨다.
 *
 * <p>{@code @DataJpaTest} 슬라이스에 {@link CartService}를 {@code @Import}하고,
 * 빈 장바구니 경로에서 호출되지 않는 {@link ProductPurchaseCatalog}는 {@code @MockitoBean}으로 대체한다.
 * 테스트 {@code application.yml}의 자동설정 제외를 리셋하고 Flyway로 V1~V3 스키마(carts/cart_items 포함)를 적용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(CartService.class)
class CartServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @MockitoBean
    private ProductPurchaseCatalog productPurchaseCatalog;

    @Autowired
    private CartService cartService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("영속된 장바구니가 없는 사용자도 NPE 없이 빈 CartView를 반환한다")
    void getCart_noPersistedCart_returnsEmptyViewWithoutError() {
        long userIdWithoutCart = 999_999L; // cart/user 행 없음

        CartService.CartView view = cartService.getCart(userIdWithoutCart);

        assertThat(view.cartId()).isZero();
        assertThat(view.items()).isEmpty();
        assertThat(view.totalQuantity()).isZero();
        assertThat(view.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.hasUnavailableItem()).isFalse();
        verifyNoInteractions(productPurchaseCatalog);
    }

    @Test
    @DisplayName("장바구니는 있으나 항목이 없으면 cartId를 유지한 빈 CartView를 반환한다")
    void getCart_existingCartNoItems_returnsEmptyViewWithCartId() {
        long userId = insertUser("cart-empty@test.com");
        Cart saved = cartRepository.save(Cart.create(userId));
        em.flush();
        em.clear();

        CartService.CartView view = cartService.getCart(userId);

        assertThat(view.cartId()).isEqualTo(saved.getId());
        assertThat(view.items()).isEmpty();
        verifyNoInteractions(productPurchaseCatalog);
    }

    /** carts.user_id FK(users) 충족용 사용자 1명 native insert 후 id 반환. */
    private long insertUser(String email) {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO users (email, password_hash, name, role) "
                                + "VALUES (?1, 'x', 'Cart User', 'CONSUMER')")
                .setParameter(1, email)
                .executeUpdate();
        return ((Number) em.getEntityManager()
                .createNativeQuery("SELECT id FROM users WHERE email = ?1")
                .setParameter(1, email)
                .getSingleResult()).longValue();
    }
}
