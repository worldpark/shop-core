package com.shop.shop.web.product;

import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.ProductFormView;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SellerProductViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>web.product 패키지로 이동 (원래 product.controller 패키지).
 * SellerProductFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>MemberRepository: @MockitoBean (JPA context 없이 기동).
 * FakeRefreshTokenStore: Redis 미기동 비파괴.
 * ProductRepository/CategoryRepository: @MockitoBean (직접 사용 없음 — facade Mock 경유).
 *
 * <p>이 테스트는 view name·model 속성·redirect·권한 차단·facade 위임에 집중한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    SellerApplicationRepository sellerApplicationRepository;

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

    /**
     * SellerProductFacade를 @MockitoBean으로 대체 — product.spi facade 격리.
     * SellerProductFacadeImpl(운영 구현체)은 이 테스트에서 mock으로 교체된다.
     * 운영 배선 검증은 ProductWiringTest에서 별도 수행.
     */
    @MockitoBean
    private SellerProductFacade sellerProductFacade;

    private static final long SELLER_ID = 2L;
    private static final long PRODUCT_ID = 10L;
    private static final String SELLER_EMAIL = "seller@example.com";

    @BeforeEach
    void setUp() {
        when(sellerProductFacade.listCategories()).thenReturn(List.of());
        when(sellerProductFacade.productStatusNames()).thenReturn(List.of("DRAFT", "ON_SALE", "SOLD_OUT", "HIDDEN"));
    }

    // ============================================================
    // GET /seller/products/new
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/new — SELLER → 200, view seller/product-form")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void newForm_seller_returns_200_with_product_form_view() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"));
    }

    @Test
    @DisplayName("GET /seller/products/new — SELLER → model에 productForm 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void newForm_seller_model_contains_productForm() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("productForm"));
    }

    @Test
    @DisplayName("GET /seller/products/new — SELLER → model에 categories 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void newForm_seller_model_contains_categories() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("categories"));
    }

    @Test
    @DisplayName("GET /seller/products/new — SELLER → model에 statuses 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void newForm_seller_model_contains_statuses() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("statuses"));
    }

    @Test
    @DisplayName("GET /seller/products/new — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void newForm_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /seller/products/new — 비인증 → /login redirect(302)")
    void newForm_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    @Test
    @DisplayName("GET /seller/products/new — ADMIN → 200(RoleHierarchy 함의)")
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void newForm_admin_returns_200() throws Exception {
        mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"));
    }

    // ============================================================
    // POST /seller/products — 성공
    // ============================================================

    @Test
    @DisplayName("POST /seller/products — 유효 폼(CSRF 포함) → 302 redirect:/seller/products/{id}/edit")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_valid_form_redirects_to_edit() throws Exception {
        when(sellerProductFacade.register(anyString(), any(), anyString(), any(), any()))
                .thenReturn(PRODUCT_ID);

        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("name", "상품A")
                        .param("basePrice", "10000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/seller/products/*/edit"));
    }

    @Test
    @DisplayName("POST /seller/products — facade.register(actorEmail, ...) 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_calls_seller_product_facade() throws Exception {
        when(sellerProductFacade.register(anyString(), any(), anyString(), any(), any()))
                .thenReturn(PRODUCT_ID);

        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("name", "상품A")
                        .param("basePrice", "10000"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductFacade).register(eq(SELLER_EMAIL), any(), eq("상품A"), any(), any());
    }

    // ============================================================
    // POST /seller/products — 검증 실패
    // ============================================================

    @Test
    @DisplayName("POST /seller/products — name 누락 → 200 seller/product-form 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_missing_name_rerenders_form() throws Exception {
        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("basePrice", "10000"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"));
    }

    @Test
    @DisplayName("POST /seller/products — 검증 실패 시 categories/statuses 재주입")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_validation_fail_repopulates_model() throws Exception {
        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("basePrice", "10000"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attributeExists("statuses"));
    }

    @Test
    @DisplayName("POST /seller/products — basePrice 음수 → 200 seller/product-form 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_negative_price_rerenders_form() throws Exception {
        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("name", "상품A")
                        .param("basePrice", "-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"));
    }

    // ============================================================
    // GET /seller/products/{id}/edit
    // ============================================================

    @Test
    @DisplayName("GET /seller/products/{id}/edit — 소유자 → 200, view seller/product-form")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void editForm_owner_returns_200() throws Exception {
        ProductFormView view = new ProductFormView(null, "상품", "설명", new BigDecimal("10000"), "DRAFT");
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID)))
                .thenReturn(view);

        mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"))
                .andExpect(model().attributeExists("productForm"))
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attributeExists("statuses"));
    }

    @Test
    @DisplayName("GET /seller/products/{id}/edit — 타인 상품 → 404(ProductAccessDeniedException)")
    @WithMockUser(username = "other@example.com", roles = "SELLER")
    void editForm_other_seller_returns_404() throws Exception {
        when(sellerProductFacade.getForEdit(eq("other@example.com"), eq(false), eq(PRODUCT_ID)))
                .thenThrow(new ProductAccessDeniedException(PRODUCT_ID));

        mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /seller/products/{id}/edit — 미존재 → 404(ProductNotFoundException)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void editForm_not_found_returns_404() throws Exception {
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(9999L)))
                .thenThrow(new ProductNotFoundException(9999L));

        mockMvc.perform(get("/seller/products/9999/edit"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // POST /seller/products/{id} — 수정
    // ============================================================

    @Test
    @DisplayName("POST /seller/products/{id} — 성공 → 302 redirect:/seller/products/{id}/edit")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void update_valid_form_redirects_to_edit() throws Exception {
        mockMvc.perform(post("/seller/products/" + PRODUCT_ID)
                        .with(csrf())
                        .param("name", "수정상품")
                        .param("basePrice", "20000")
                        .param("status", "ON_SALE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/seller/products/*/edit"));

        verify(sellerProductFacade).update(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID),
                any(), eq("수정상품"), any(), any(), eq("ON_SALE"));
    }

    @Test
    @DisplayName("POST /seller/products/{id} — 검증 실패 → 200 seller/product-form 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void update_validation_fail_rerenders_form() throws Exception {
        mockMvc.perform(post("/seller/products/" + PRODUCT_ID)
                        .with(csrf())
                        .param("basePrice", "1000"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"))
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attributeExists("statuses"));
    }
}
