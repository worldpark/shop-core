package com.shop.shop.web.member;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.AdminBootstrapFacade;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * LoginViewController ADMIN 부트스트랩 게이트 슬라이스 테스트.
 *
 * <p>{@link AdminBootstrapFacade} (@MockitoBean)으로 member 도메인 로직을 격리한다.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /login: adminExists=false → redirect:/setup/admin (부트스트랩 게이트)</li>
 *   <li>GET /login: adminExists=true → 200 auth/login (기존 로그인 화면)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class LoginViewControllerBootstrapTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminBootstrapFacade adminBootstrapFacade;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private SellerApplicationRepository sellerApplicationRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private OptionValueRepository optionValueRepository;

    @MockitoBean
    private ProductVariantRepository productVariantRepository;

    @MockitoBean
    private ProductImageRepository productImageRepository;

    @MockitoBean
    private CartRepository cartRepository;

    @MockitoBean
    private CartItemRepository cartItemRepository;

    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @Test
    @DisplayName("GET /login: adminExists=false → redirect:/setup/admin (ADMIN 0명 부트스트랩 게이트)")
    void getLogin_noAdmin_redirectsToSetupAdmin() throws Exception {
        when(adminBootstrapFacade.adminExists()).thenReturn(false);

        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup/admin"));
    }

    @Test
    @DisplayName("GET /login: adminExists=true → 200 auth/login (기존 로그인 화면 정상 반환)")
    void getLogin_adminExists_rendersLoginPage() throws Exception {
        when(adminBootstrapFacade.adminExists()).thenReturn(true);

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }
}
