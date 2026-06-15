package com.shop.shop.product;

import com.shop.shop.member.adapter.MemberUserDirectoryAdapter;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.controller.AdminCategoryRestController;
import com.shop.shop.product.controller.CategoryRestController;
import com.shop.shop.product.controller.SellerProductRestController;
import com.shop.shop.web.product.SellerProductViewController;
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
import com.shop.shop.product.service.CategoryService;
import com.shop.shop.product.service.CategoryServiceResponse;
import com.shop.shop.product.service.ProductService;
import com.shop.shop.product.service.ProductServiceResponse;
import com.shop.shop.product.spi.UserDirectory;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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
 * product 모듈 신규 진입 빈 운영 배선 회귀 방지 테스트 (P1/testing-rule).
 *
 * <p>FakeRefreshTokenStore를 @Import해 Redis 미기동 환경에서도 컨텍스트가 기동된다.
 * MemberRepository/MemberUserDetailsService/CategoryRepository/ProductRepository는
 * @MockitoBean으로 JPA/DB 의존을 격리한다.
 *
 * <p>포트-어댑터 운영 배선 단언:
 * {@link MemberUserDirectoryAdapter}가 {@link UserDirectory} 빈으로 등록되고,
 * 컨텍스트에서 {@link UserDirectory} 타입이 어댑터로 단일 운영 배선됨을 단언.
 * ({@link SellerProductViewController}의 UserDirectory 주입이 운영에서 해결됨 확인)
 *
 * <p>NOTE: UserDirectory를 @MockitoBean하지 않는다 — 운영 배선(어댑터)이 실제 빈으로 등록되는지 확인 목적.
 * MemberService는 MemberRepository @MockitoBean이 있으므로 운영 빈으로 생성 가능.
 * (MemberService→MemberRepository 경로는 빈 존재만 확인, 실제 조회 불요)
 *
 * <p>AdminMemberWiringTest 패턴 계승.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class ProductWiringTest {

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

    // ============================================================
    // 신규 진입 빈 등록 단언
    // ============================================================

    @Test
    @DisplayName("운영 배선: CategoryRestController 빈이 컨텍스트에 등록된다")
    void categoryRestController_bean_is_registered() {
        assertThat(context.getBean(CategoryRestController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: AdminCategoryRestController 빈이 컨텍스트에 등록된다")
    void adminCategoryRestController_bean_is_registered() {
        assertThat(context.getBean(AdminCategoryRestController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerProductRestController 빈이 컨텍스트에 등록된다")
    void sellerProductRestController_bean_is_registered() {
        assertThat(context.getBean(SellerProductRestController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: SellerProductViewController 빈이 컨텍스트에 등록된다")
    void sellerProductViewController_bean_is_registered() {
        assertThat(context.getBean(SellerProductViewController.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: CategoryServiceResponse 빈이 컨텍스트에 등록된다")
    void categoryServiceResponse_bean_is_registered() {
        assertThat(context.getBean(CategoryServiceResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: ProductServiceResponse 빈이 컨텍스트에 등록된다")
    void productServiceResponse_bean_is_registered() {
        assertThat(context.getBean(ProductServiceResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: CategoryService 빈이 컨텍스트에 등록된다")
    void categoryService_bean_is_registered() {
        assertThat(context.getBean(CategoryService.class)).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: ProductService 빈이 컨텍스트에 등록된다")
    void productService_bean_is_registered() {
        assertThat(context.getBean(ProductService.class)).isNotNull();
    }

    // ============================================================
    // 포트-어댑터 운영 배선 단언 (핵심)
    // ============================================================

    @Test
    @DisplayName("포트-어댑터 배선: UserDirectory 타입이 MemberUserDirectoryAdapter로 단일 등록된다")
    void userDirectory_is_member_user_directory_adapter() {
        UserDirectory userDirectory = context.getBean(UserDirectory.class);
        assertThat(userDirectory).isInstanceOf(MemberUserDirectoryAdapter.class);
    }

    @Test
    @DisplayName("포트-어댑터 배선: MemberUserDirectoryAdapter 빈이 컨텍스트에 등록된다")
    void memberUserDirectoryAdapter_bean_is_registered() {
        assertThat(context.getBean(MemberUserDirectoryAdapter.class)).isNotNull();
    }

    @Test
    @DisplayName("포트-어댑터 배선: SellerProductViewController가 SellerProductFacade를 주입받아 운영에서 해결된다")
    void sellerProductViewController_has_seller_product_facade_injected() {
        SellerProductViewController controller = context.getBean(SellerProductViewController.class);
        // SellerProductViewController(web)가 SellerProductFacade를 주입받아 운영에서 해결됨
        assertThat(controller).isNotNull();
        // UserDirectory 빈이 어댑터로 단일 해결됨을 간접 확인 (SellerProductFacadeImpl 내부 사용)
        UserDirectory userDirectory = context.getBean(UserDirectory.class);
        assertThat(userDirectory).isInstanceOf(MemberUserDirectoryAdapter.class);
    }
}
