package com.shop.shop.member;

import com.shop.shop.member.controller.MemberRestController;
import com.shop.shop.web.member.MemberSignupViewController;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberServiceResponse;
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
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
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
 * 회원가입 관련 빈 운영 배선 회귀 방지 테스트 (P1/testing-rule).
 *
 * <p>FakeRefreshTokenStore를 @Import해 Redis 미기동 환경에서도 컨텍스트가 기동된다.
 * MemberRepository, MemberUserDetailsService는 @MockitoBean으로 JPA/DB 의존을 격리한다.
 *
 * <p>신규 진입 빈(MemberRestController, MemberServiceResponse, MemberSignupViewController)이
 * 운영 컴포넌트 스캔에서 실제로 등록되는지 단언한다.
 * fake가 신규 배선을 가리지 않음을 확인 (testing-rule P1 — fake 미import 없이 실 빈 확인).
 *
 * <p>RefreshTokenStore의 운영 구현(RedisRefreshTokenStore) 배선은
 * {@code RefreshTokenStoreWiringTest}에서 이미 보장됨 — 이 테스트는 변경하지 않는다.
 * 006 AdminMemberWiringTest 패턴 계승.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class MemberSignupWiringTest {

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


    @Test
    @DisplayName("운영 배선: MemberRestController 빈이 컨텍스트에 등록된다")
    void memberRestController_bean_is_registered() {
        MemberRestController bean = context.getBean(MemberRestController.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: MemberServiceResponse 빈이 컨텍스트에 등록된다")
    void memberServiceResponse_bean_is_registered() {
        MemberServiceResponse bean = context.getBean(MemberServiceResponse.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("운영 배선: MemberSignupViewController 빈이 컨텍스트에 등록된다")
    void memberSignupViewController_bean_is_registered() {
        MemberSignupViewController bean = context.getBean(MemberSignupViewController.class);
        assertThat(bean).isNotNull();
    }
}
