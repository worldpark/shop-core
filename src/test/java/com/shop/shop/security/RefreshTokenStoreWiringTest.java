package com.shop.shop.security;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 배선 회귀 방지 테스트.
 *
 * <p>FakeRefreshTokenStore를 @Import하지 않으므로,
 * 컴포넌트 스캔 + Redis 오토컨피그 조건에서 RefreshTokenStore 빈이
 * RedisRefreshTokenStore로 배선되는지 검증한다.
 *
 * <p>이 테스트는 @ConditionalOnBean(StringRedisTemplate.class)가 있을 때 실패한다.
 * 수정 후(@ConditionalOnBean 제거 + RedisAutoConfiguration 활성화) 통과해야 한다.
 *
 * <p>Lettuce 지연 연결: Redis 브로커 없이도 빈 생성 및 컨텍스트 로드가 통과한다.
 * 연결은 첫 명령 시점에 시도되므로 빈 배선 검증만 수행하는 이 테스트는 브로커가 없어도 동작한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@MockSharedRepositories
class RefreshTokenStoreWiringTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    MemberRepository memberRepository;

    @MockitoBean
    SellerApplicationRepository sellerApplicationRepository;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    CategoryRepository categoryRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductOptionRepository productOptionRepository;

    @MockitoBean
    OptionValueRepository optionValueRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ProductImageRepository productImageRepository;

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartItemRepository cartItemRepository;

    @MockitoBean
    InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    OrderRepository orderRepository;

    @MockitoBean
    ShipmentRepository shipmentRepository;

    @MockitoBean
    PaymentRepository paymentRepository;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @Test
    @DisplayName("운영 배선: FakeRefreshTokenStore 없이 RefreshTokenStore 빈이 RedisRefreshTokenStore로 등록된다")
    void refreshTokenStore_bean_is_RedisRefreshTokenStore_without_fake() {
        RefreshTokenStore store = context.getBean(RefreshTokenStore.class);
        assertThat(store).isInstanceOf(RedisRefreshTokenStore.class);
    }
}
