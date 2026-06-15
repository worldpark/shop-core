package com.shop.shop.member;

import com.shop.shop.member.controller.AdminSellerApplicationRestController;
import com.shop.shop.member.controller.SellerApplicationRestController;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.AdminSellerApplicationServiceResponse;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.service.SellerApplicationService;
import com.shop.shop.member.service.SellerApplicationServiceResponse;
import com.shop.shop.member.spi.AdminSellerApplicationFacade;
import com.shop.shop.member.spi.SellerApplicationFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import com.shop.shop.web.member.AdminSellerApplicationViewController;
import com.shop.shop.web.member.SellerApplicationViewController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매자 신청 워크플로우 빈 운영 배선 회귀 방지 테스트 (verification-gate-rule §4).
 *
 * <p>FakeRefreshTokenStore를 @Import해 Redis 미기동 환경에서도 컨텍스트가 기동된다.
 * Repository들은 @MockitoBean으로 JPA/DB 의존을 격리한다.
 *
 * <p>신규 진입 빈이 운영 컴포넌트 스캔에서 실제로 등록되는지 단언한다:
 * <ul>
 *   <li>SellerApplicationRestController</li>
 *   <li>AdminSellerApplicationRestController</li>
 *   <li>SellerApplicationServiceResponse</li>
 *   <li>AdminSellerApplicationServiceResponse</li>
 *   <li>SellerApplicationService</li>
 *   <li>SellerApplicationFacade (인터페이스 — 구현체 SellerApplicationFacadeImpl)</li>
 *   <li>AdminSellerApplicationFacade (인터페이스 — 구현체 AdminSellerApplicationFacadeImpl)</li>
 *   <li>SellerApplicationViewController</li>
 *   <li>AdminSellerApplicationViewController</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerApplicationWiringTest {

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
    @DisplayName("운영 배선: SellerApplicationRestController 빈이 컨텍스트에 등록된다")
    void sellerApplicationRestController_bean_is_registered() {
        assertThat(context.getBean(SellerApplicationRestController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminSellerApplicationRestController 빈이 컨텍스트에 등록된다")
    void adminSellerApplicationRestController_bean_is_registered() {
        assertThat(context.getBean(AdminSellerApplicationRestController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerApplicationServiceResponse 빈이 컨텍스트에 등록된다")
    void sellerApplicationServiceResponse_bean_is_registered() {
        assertThat(context.getBean(SellerApplicationServiceResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminSellerApplicationServiceResponse 빈이 컨텍스트에 등록된다")
    void adminSellerApplicationServiceResponse_bean_is_registered() {
        assertThat(context.getBean(AdminSellerApplicationServiceResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerApplicationService 빈이 컨텍스트에 등록된다")
    void sellerApplicationService_bean_is_registered() {
        assertThat(context.getBean(SellerApplicationService.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerApplicationFacade(구현체) 빈이 컨텍스트에 등록된다")
    void sellerApplicationFacade_bean_is_registered() {
        assertThat(context.getBean(SellerApplicationFacade.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminSellerApplicationFacade(구현체) 빈이 컨텍스트에 등록된다")
    void adminSellerApplicationFacade_bean_is_registered() {
        assertThat(context.getBean(AdminSellerApplicationFacade.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerApplicationViewController 빈이 컨텍스트에 등록된다")
    void sellerApplicationViewController_bean_is_registered() {
        assertThat(context.getBean(SellerApplicationViewController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminSellerApplicationViewController 빈이 컨텍스트에 등록된다")
    void adminSellerApplicationViewController_bean_is_registered() {
        assertThat(context.getBean(AdminSellerApplicationViewController.class)).isNotNull();
    }
}
