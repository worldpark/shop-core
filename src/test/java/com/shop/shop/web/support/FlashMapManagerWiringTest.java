package com.shop.shop.web.support;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.FlashMapManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code flashMapManager} 빈 배선 회귀 가드.
 *
 * <p>FakeRefreshTokenStore를 @Import하지 않고 운영 컴포넌트 스캔과 자동설정 그대로 컨텍스트를 띄워
 * {@code flashMapManager} 빈이 {@link CookieFlashMapManager}로 등록되는지 단언한다.
 *
 * <p>이 테스트가 가드하는 시나리오:
 * <ul>
 *   <li>{@link FlashCookieConfig}의 빈 이름이 {@code flashMapManager}가 아닌 경우 →
 *       Spring MVC가 기본 {@code SessionFlashMapManager}를 선택 → 이 테스트 FAIL</li>
 *   <li>{@link FlashCookieConfig}가 컴포넌트 스캔에서 누락된 경우 → 동일하게 FAIL</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@MockSharedRepositories
class FlashMapManagerWiringTest {

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
    CouponRepository couponRepository;

    @MockitoBean
    UserCouponRepository userCouponRepository;

    @MockitoBean
    OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    ReviewRepository reviewRepository;

    @Test
    @DisplayName("운영 배선: flashMapManager 빈이 CookieFlashMapManager로 등록되어 SessionFlashMapManager를 대체한다")
    void flashMapManager_bean_is_CookieFlashMapManager() {
        FlashMapManager flashMapManager = context.getBean("flashMapManager", FlashMapManager.class);
        assertThat(flashMapManager)
                .as("flashMapManager 빈은 CookieFlashMapManager여야 한다 (SessionFlashMapManager 대체 확인)")
                .isInstanceOf(CookieFlashMapManager.class);
    }
}
