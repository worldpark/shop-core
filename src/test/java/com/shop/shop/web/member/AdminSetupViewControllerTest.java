package com.shop.shop.web.member;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.AdminAlreadyExistsException;
import com.shop.shop.common.exception.DuplicateEmailException;
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * AdminSetupViewController MockMvc 슬라이스 테스트.
 *
 * <p>{@link AdminBootstrapFacade} (@MockitoBean)으로 member 도메인 로직을 격리한다.
 * CSRF 토큰 포함 요청으로 테스트 (permitAll이지만 CSRF 보호 유지).
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /setup/admin: adminExists=true → redirect:/login (부트스트랩 폐쇄)</li>
 *   <li>GET /setup/admin: adminExists=false → 200 auth/admin-setup + 모델 adminSetupForm</li>
 *   <li>POST /setup/admin 검증 실패 → 200 재렌더 + 비번 clear</li>
 *   <li>POST /setup/admin 성공 → redirect:/login?adminCreated</li>
 *   <li>POST /setup/admin AdminAlreadyExistsException → redirect:/login</li>
 *   <li>POST /setup/admin DuplicateEmailException → 200 재렌더 + email 필드 에러</li>
 *   <li>POST /setup/admin CSRF 없음 → 403</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminSetupViewControllerTest {

    private static final String EMAIL = "admin@example.com";
    private static final String PASSWORD = "adminpass1";
    private static final String NAME = "관리자";

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

    // ─── GET /setup/admin ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /setup/admin: adminExists=true → redirect:/login (부트스트랩 폐쇄)")
    void getSetupAdmin_adminExists_redirectsToLogin() throws Exception {
        when(adminBootstrapFacade.adminExists()).thenReturn(true);

        mockMvc.perform(get("/setup/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("GET /setup/admin: adminExists=false → 200 auth/admin-setup + 모델 adminSetupForm")
    void getSetupAdmin_noAdmin_rendersForm() throws Exception {
        when(adminBootstrapFacade.adminExists()).thenReturn(false);

        mockMvc.perform(get("/setup/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attribute("adminSetupForm", notNullValue()));
    }

    // ─── POST /setup/admin ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /setup/admin 성공 → redirect:/login?adminCreated")
    void postSetupAdmin_success_redirectsToLoginWithAdminCreated() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?adminCreated"));

        verify(adminBootstrapFacade).createFirstAdmin(EMAIL, PASSWORD, NAME);
    }

    @Test
    @DisplayName("POST /setup/admin 이메일 형식 오류(검증 실패) → 200 재렌더 auth/admin-setup, facade 미호출")
    void postSetupAdmin_invalidEmail_rerenderForm() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", "not-an-email")
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attributeHasFieldErrors("adminSetupForm", "email"));

        verify(adminBootstrapFacade, never()).createFirstAdmin(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /setup/admin 비밀번호 최소 길이 미달 → 200 재렌더 + password 필드 에러")
    void postSetupAdmin_shortPassword_rerenderForm() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", "short")
                        .param("passwordConfirm", "short")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attributeHasFieldErrors("adminSetupForm", "password"));

        verify(adminBootstrapFacade, never()).createFirstAdmin(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /setup/admin 비밀번호 불일치 → 200 재렌더 + passwordConfirm 필드 에러")
    void postSetupAdmin_passwordMismatch_rerenderForm() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", "differentpass")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attributeHasFieldErrors("adminSetupForm", "passwordConfirm"));

        verify(adminBootstrapFacade, never()).createFirstAdmin(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /setup/admin 검증 실패 시 비번 필드 clear (password=null, passwordConfirm=null)")
    void postSetupAdmin_validationFailure_clearsPasswordFields() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", "not-an-email")
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("password", nullValue())))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("passwordConfirm", nullValue())));
    }

    @Test
    @DisplayName("POST /setup/admin AdminAlreadyExistsException → redirect:/login (이미 닫힘)")
    void postSetupAdmin_adminAlreadyExists_redirectsToLogin() throws Exception {
        doThrow(new AdminAlreadyExistsException())
                .when(adminBootstrapFacade).createFirstAdmin(anyString(), anyString(), anyString());

        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("POST /setup/admin DuplicateEmailException → 200 재렌더 + email 필드 에러 + 비번 clear")
    void postSetupAdmin_duplicateEmail_rerenderFormWithEmailError() throws Exception {
        doThrow(new DuplicateEmailException())
                .when(adminBootstrapFacade).createFirstAdmin(anyString(), anyString(), anyString());

        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attributeHasFieldErrors("adminSetupForm", "email"))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("password", nullValue())))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("passwordConfirm", nullValue())));
    }

    @Test
    @DisplayName("POST /setup/admin 이름 필드 빈 값 → 200 재렌더 + name 필드 에러")
    void postSetupAdmin_emptyName_rerenderForm() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attributeHasFieldErrors("adminSetupForm", "name"));
    }

    @Test
    @DisplayName("POST /setup/admin CSRF 토큰 없음 → 403 Forbidden")
    void postSetupAdmin_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .param("email", EMAIL)
                        .param("password", PASSWORD)
                        .param("passwordConfirm", PASSWORD)
                        .param("name", NAME))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /setup/admin 이메일 유지 (검증 실패 시 이메일/name은 유지, 비번만 clear)")
    void postSetupAdmin_validationFailure_preservesEmailAndName() throws Exception {
        mockMvc.perform(post("/setup/admin")
                        .with(csrf())
                        .param("email", EMAIL)
                        .param("password", "short")
                        .param("passwordConfirm", "short")
                        .param("name", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-setup"))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("email", equalTo(EMAIL))))
                .andExpect(model().attribute("adminSetupForm",
                        hasProperty("name", equalTo(NAME))));
    }
}
