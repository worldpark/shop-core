package com.shop.shop.product.controller;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductStatus;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.spi.UserDirectory;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.common.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
 * <p>UserDirectory를 @MockBean으로 주입 — member 의존 없이 View 단독 검증(포트-어댑터 격리).
 * MemberUserDirectoryAdapter(운영 어댑터)는 이 테스트에서 검증되지 않음 (ProductWiringTest에서 별도 단언).
 *
 * <p>뷰 실제 HTML 렌더링 단언은 view-implementor 단계로 미룬다 (템플릿 미작성 상태).
 * 이 테스트는 view name·model 속성·redirect·권한 차단·서비스 위임에 집중한다.
 *
 * <!-- NOTE: HTML 렌더링 단언(th:errors echo, CSRF 히든 마커 실제 내용)은
 *      view-implementor가 seller/product-form.html 작성 후 별도 ViewRenderingTest에서 검증한다. -->
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    /**
     * UserDirectory를 @MockBean으로 대체 — product.spi 포트 격리.
     * member.adapter.MemberUserDirectoryAdapter(운영 어댑터)는 이 테스트에서 mock으로 교체된다.
     * 운영 배선 검증은 ProductWiringTest에서 별도 수행.
     */
    @MockBean
    private UserDirectory userDirectory;

    private static final long SELLER_ID = 2L;
    private static final long PRODUCT_ID = 10L;
    private static final String SELLER_EMAIL = "seller@example.com";

    @BeforeEach
    void setUp() {
        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        // View principal 통일: email → userId stub
        when(userDirectory.findUserIdByEmail(SELLER_EMAIL)).thenReturn(SELLER_ID);
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
        when(userDirectory.findUserIdByEmail("admin@example.com")).thenReturn(1L);
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
        Product saved = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.save(any())).thenReturn(saved);

        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("name", "상품A")
                        .param("basePrice", "10000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/seller/products/*/edit"));
    }

    @Test
    @DisplayName("POST /seller/products — UserDirectory로 actorId 획득 후 ProductService.register 호출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void register_calls_user_directory_and_product_service() throws Exception {
        Product saved = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.save(any())).thenReturn(saved);

        mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("name", "상품A")
                        .param("basePrice", "10000"))
                .andExpect(status().is3xxRedirection());

        verify(userDirectory).findUserIdByEmail(SELLER_EMAIL);
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
                // name 누락 → 검증 실패 → 재렌더
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
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

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
        when(userDirectory.findUserIdByEmail("other@example.com")).thenReturn(99L);
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID); // 소유자는 SELLER_ID
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        // ProductAccessDeniedException → ViewExceptionHandler → error/error view
        // NOTE: 실제 뷰 렌더링(error/error.html 미작성)은 view-implementor 단계에서 검증
        mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /seller/products/{id}/edit — 미존재 → 404(ProductNotFoundException)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void editForm_not_found_returns_404() throws Exception {
        when(productRepository.findById(9999L)).thenReturn(Optional.empty());

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
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        mockMvc.perform(post("/seller/products/" + PRODUCT_ID)
                        .with(csrf())
                        .param("name", "수정상품")
                        .param("basePrice", "20000")
                        .param("status", "ON_SALE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/seller/products/*/edit"));
    }

    @Test
    @DisplayName("POST /seller/products/{id} — 검증 실패 → 200 seller/product-form 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void update_validation_fail_rerenders_form() throws Exception {
        mockMvc.perform(post("/seller/products/" + PRODUCT_ID)
                        .with(csrf())
                        .param("basePrice", "1000"))
                // name 누락 → 검증 실패
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-form"))
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attributeExists("statuses"));
    }

    // ============================================================
    // helpers
    // ============================================================

    private Product sampleProduct(long ownerId, long productId) {
        Product product = Product.create(ownerId, null, "상품", "설명", new BigDecimal("10000"));
        try {
            var idField = Product.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, productId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return product;
    }
}
