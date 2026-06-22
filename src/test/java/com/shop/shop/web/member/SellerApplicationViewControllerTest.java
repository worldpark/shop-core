package com.shop.shop.web.member;

import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.dto.SellerApplicationEligibility;
import com.shop.shop.member.dto.SellerApplicationResponse;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.member.spi.SellerApplicationFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SellerApplicationViewController MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /seller-applications/apply: 200 + view name + model eligible/form</li>
 *   <li>GET /seller-applications/apply: SELLER/ADMIN 진입 시 eligible=false + reason(차단 아님)</li>
 *   <li>POST /seller-applications: 성공 → redirect+flashSuccess</li>
 *   <li>POST /seller-applications: 검증 실패 → 재렌더</li>
 *   <li>GET /seller-applications/me: application 있음/없음 분기</li>
 *   <li>비인증 → /login redirect</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerApplicationViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerApplicationFacade sellerApplicationFacade;

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

    // ============================================================
    // GET /seller-applications/apply
    // ============================================================

    @Test
    @WithMockUser(username = "consumer@test.com", roles = "CONSUMER")
    @DisplayName("GET /seller-applications/apply — 인증 CONSUMER → 200 + view 'seller-applications/apply' + model eligible=true")
    void apply_form_authenticated_consumer_returns_200_with_eligible_model() throws Exception {
        when(sellerApplicationFacade.checkEligibility("consumer@test.com"))
                .thenReturn(new SellerApplicationEligibility(true, null));

        mockMvc.perform(get("/seller-applications/apply"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller-applications/apply"))
                .andExpect(model().attributeExists("eligible"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attribute("eligible", true));
    }

    @Test
    @WithMockUser(username = "seller@test.com", roles = "SELLER")
    @DisplayName("GET /seller-applications/apply — 인증 SELLER → 200(차단 아님) + eligible=false + reason")
    void apply_form_seller_enters_and_gets_ineligible_model() throws Exception {
        when(sellerApplicationFacade.checkEligibility("seller@test.com"))
                .thenReturn(new SellerApplicationEligibility(false, "이미 판매자 이상 권한입니다."));

        mockMvc.perform(get("/seller-applications/apply"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller-applications/apply"))
                .andExpect(model().attribute("eligible", false))
                .andExpect(model().attribute("reason", "이미 판매자 이상 권한입니다."));
    }

    @Test
    @DisplayName("GET /seller-applications/apply — 비인증 → /login redirect")
    void apply_form_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/seller-applications/apply"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // POST /seller-applications
    // ============================================================

    @Test
    @WithMockUser(username = "consumer@test.com", roles = "CONSUMER")
    @DisplayName("POST /seller-applications — 유효 폼 + 성공 → redirect:/seller-applications/me + flashSuccess")
    void apply_post_valid_form_redirects_with_flash_success() throws Exception {
        mockMvc.perform(post("/seller-applications")
                        .with(csrf())
                        .param("businessName", "테스트상회")
                        .param("businessRegistrationNumber", "1234567890")
                        .param("contactPhone", "010-0000-0000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seller-applications/me"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(username = "consumer@test.com", roles = "CONSUMER")
    @DisplayName("POST /seller-applications — businessRegistrationNumber 형식 오류(@Valid) → 400 재렌더")
    void apply_post_invalid_business_number_rerenders_form() throws Exception {
        when(sellerApplicationFacade.checkEligibility(anyString()))
                .thenReturn(new SellerApplicationEligibility(true, null));

        mockMvc.perform(post("/seller-applications")
                        .with(csrf())
                        .param("businessName", "테스트상회")
                        .param("businessRegistrationNumber", "123") // 10자리 미만
                        .param("contactPhone", "010-0000-0000"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller-applications/apply"));
    }

    @Test
    @DisplayName("POST /seller-applications — 비인증 → /login redirect")
    void apply_post_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(post("/seller-applications")
                        .with(csrf())
                        .param("businessName", "테스트상회")
                        .param("businessRegistrationNumber", "1234567890")
                        .param("contactPhone", "010-0000-0000"))
                .andExpect(status().is3xxRedirection());
    }

    // ============================================================
    // GET /seller-applications/me
    // ============================================================

    @Test
    @WithMockUser(username = "consumer@test.com", roles = "CONSUMER")
    @DisplayName("GET /seller-applications/me — 신청 이력 있음 → 200 + view + model sellerApplication 존재")
    void me_with_application_returns_200_with_application_model() throws Exception {
        SellerApplication app = SellerApplication.submit(10L, "상호", "1234567890", "010-0000-0000");
        setId(app, 100L);
        // 영속 전 엔티티라 BaseEntity.createdAt(@CreatedDate)이 null — 실DB에선 항상 채워지는 NOT NULL 감사 컬럼이다.
        // me.html 신청일 렌더(createdAt.atZone)는 createdAt 존재를 전제하므로 픽스처도 현실대로 세팅한다(id 세팅과 동형).
        setCreatedAt(app, Instant.parse("2026-01-01T00:00:00Z"));
        SellerApplicationResponse response = SellerApplicationResponse.from(app);
        when(sellerApplicationFacade.findMyApplication("consumer@test.com"))
                .thenReturn(Optional.of(response));

        // 모델 키는 'sellerApplication' — 'application'은 Thymeleaf 암묵 scope 객체와 충돌(E2E로 발견)
        mockMvc.perform(get("/seller-applications/me"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller-applications/me"))
                .andExpect(model().attributeExists("sellerApplication"));
    }

    @Test
    @WithMockUser(username = "consumer@test.com", roles = "CONSUMER")
    @DisplayName("GET /seller-applications/me — 이력 없음 → 200 + view + model sellerApplication=null(안내 화면)")
    void me_without_application_returns_200_with_null_application() throws Exception {
        when(sellerApplicationFacade.findMyApplication("consumer@test.com"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/seller-applications/me"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller-applications/me"))
                .andExpect(model().attribute("sellerApplication", (Object) null));
    }

    @Test
    @DisplayName("GET /seller-applications/me — 비인증 → /login redirect")
    void me_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/seller-applications/me"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private void setId(SellerApplication app, long id) {
        try {
            var idField = SellerApplication.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(app, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** BaseEntity.createdAt(@CreatedDate, 상위 클래스 private)을 리플렉션으로 세팅한다. */
    private void setCreatedAt(SellerApplication app, Instant ts) {
        for (Class<?> c = app.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(app, ts);
                return;
            } catch (NoSuchFieldException ignored) {
                // 상위 클래스(BaseEntity)에서 계속 탐색
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("createdAt 필드를 찾지 못했습니다");
    }
}
