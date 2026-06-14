package com.shop.shop.web.product;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.ProductAccessDeniedException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.OptionValueResponse;
import com.shop.shop.product.dto.ProductOptionResponse;
import com.shop.shop.product.dto.ProductVariantResponse;
import com.shop.shop.product.dto.SellerProductRef;
import com.shop.shop.product.dto.VariantManagementView;
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
import com.shop.shop.product.spi.SellerProductVariantFacade;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SellerProductVariantViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>SellerProductVariantFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>DB/JPA/Flyway/Kafka 자동설정 제외 프로파일(test)로 기동.
 * FakeRefreshTokenStore: Redis 미기동 비파괴.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET SELLER → 200, view name, 모델 키 6종</li>
 *   <li>GET CONSUMER → 403, 비인증 → redirect(/login), ADMIN → 200</li>
 *   <li>POST 옵션/옵션값/variant 생성 성공 → 302 redirect + facade 호출 verify</li>
 *   <li>@Valid 검증 실패 → 200 + view seller/product-variants 재렌더</li>
 *   <li>BusinessException → flashError redirect</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductVariantViewControllerTest {

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
     * SellerProductVariantFacade를 @MockitoBean으로 대체 — product.spi facade 격리.
     * 운영 배선 검증은 별도 WiringTest에서 수행.
     */
    @MockitoBean
    private SellerProductVariantFacade sellerProductVariantFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;


    private static final long PRODUCT_ID = 10L;
    private static final long OPTION_ID = 20L;
    private static final long VARIANT_ID = 30L;
    private static final String SELLER_EMAIL = "seller@example.com";

    private static final String BASE_URL = "/seller/products/" + PRODUCT_ID + "/variants";

    private VariantManagementView stubView;

    @BeforeEach
    void setUp() {
        SellerProductRef productRef = new SellerProductRef(PRODUCT_ID, "테스트 상품");
        List<ProductOptionResponse> options = List.of(
                new ProductOptionResponse(OPTION_ID, "색상",
                        List.of(new OptionValueResponse(100L, OPTION_ID, "빨강"),
                                new OptionValueResponse(101L, OPTION_ID, "파랑")))
        );
        List<ProductVariantResponse> variants = List.of(
                new ProductVariantResponse(VARIANT_ID, "SKU-001", new BigDecimal("10000"), 5, true,
                        List.of(100L), List.of("빨강"))
        );
        stubView = new VariantManagementView(productRef, options, variants);

        when(sellerProductVariantFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(stubView);
    }

    // ============================================================
    // GET /seller/products/{productId}/variants
    // ============================================================

    @Test
    @DisplayName("GET variants — SELLER → 200, view seller/product-variants")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void get_seller_returns_200_with_correct_view() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"));
    }

    @Test
    @DisplayName("GET variants — SELLER → 모델 키 6종(product/options/variants/optionForm/optionValueForm/variantForm) 존재")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void get_seller_model_contains_all_required_attributes() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("product"))
                .andExpect(model().attributeExists("options"))
                .andExpect(model().attributeExists("variants"))
                .andExpect(model().attributeExists("optionForm"))
                .andExpect(model().attributeExists("optionValueForm"))
                .andExpect(model().attributeExists("variantForm"));
    }

    @Test
    @DisplayName("GET variants — CONSUMER → 403")
    @WithMockUser(username = "consumer@example.com", roles = "CONSUMER")
    void get_consumer_returns_403() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET variants — 비인증 → /login redirect(302)")
    void get_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login**"));
    }

    @Test
    @DisplayName("GET variants — ADMIN → 200(RoleHierarchy 함의)")
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void get_admin_returns_200() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"));
    }

    // ============================================================
    // POST /options — 옵션 생성
    // ============================================================

    @Test
    @DisplayName("POST /options — 성공(CSRF) → 302 redirect:/seller/products/{productId}/variants")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOption_success_redirects() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options")
                        .with(csrf())
                        .param("name", "색상"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    @Test
    @DisplayName("POST /options — 성공 → facade.createOption 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOption_success_calls_facade() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options")
                        .with(csrf())
                        .param("name", "색상"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductVariantFacade).createOption(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq("색상"));
    }

    @Test
    @DisplayName("POST /options — name 누락(@Valid 실패) → 200 seller/product-variants 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOption_validation_fail_rerenders() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"))
                .andExpect(model().attributeExists("product"))
                .andExpect(model().attributeExists("options"))
                .andExpect(model().attributeExists("variants"))
                .andExpect(model().attributeExists("optionForm"))
                .andExpect(model().attributeExists("optionValueForm"))
                .andExpect(model().attributeExists("variantForm"));
    }

    @Test
    @DisplayName("POST /options — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOption_businessException_redirects_with_error() throws Exception {
        doThrow(new BusinessException("이미 사용 중인 옵션명입니다."))
                .when(sellerProductVariantFacade)
                .createOption(anyString(), anyBoolean(), anyLong(), anyString());

        mockMvc.perform(post(BASE_URL + "/options")
                        .with(csrf())
                        .param("name", "색상"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    // ============================================================
    // POST /options/{optionId}/values — 옵션값 생성
    // ============================================================

    @Test
    @DisplayName("POST /options/{optionId}/values — 성공 → 302 redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOptionValue_success_redirects() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options/" + OPTION_ID + "/values")
                        .with(csrf())
                        .param("value", "빨강"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    @Test
    @DisplayName("POST /options/{optionId}/values — 성공 → facade.createOptionValue 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOptionValue_success_calls_facade() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options/" + OPTION_ID + "/values")
                        .with(csrf())
                        .param("value", "빨강"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductVariantFacade).createOptionValue(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq(OPTION_ID), eq("빨강"));
    }

    @Test
    @DisplayName("POST /options/{optionId}/values — value 누락(@Valid 실패) → 200 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOptionValue_validation_fail_rerenders() throws Exception {
        mockMvc.perform(post(BASE_URL + "/options/" + OPTION_ID + "/values")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"));
    }

    @Test
    @DisplayName("POST /options/{optionId}/values — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createOptionValue_businessException_redirects_with_error() throws Exception {
        doThrow(new BusinessException("이미 사용 중인 옵션값입니다."))
                .when(sellerProductVariantFacade)
                .createOptionValue(anyString(), anyBoolean(), anyLong(), anyLong(), anyString());

        mockMvc.perform(post(BASE_URL + "/options/" + OPTION_ID + "/values")
                        .with(csrf())
                        .param("value", "빨강"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    // ============================================================
    // POST /variants — variant 생성
    // ============================================================

    @Test
    @DisplayName("POST /variants — 성공 → 302 redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createVariant_success_redirects() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(csrf())
                        .param("sku", "SKU-NEW")
                        .param("price", "15000")
                        .param("stock", "10")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    @Test
    @DisplayName("POST /variants — 성공 → facade.createVariant 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createVariant_success_calls_facade() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(csrf())
                        .param("sku", "SKU-NEW")
                        .param("price", "15000")
                        .param("stock", "10")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductVariantFacade).createVariant(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID),
                eq("SKU-NEW"), eq(new BigDecimal("15000")), eq(10), eq(true), anyList());
    }

    @Test
    @DisplayName("POST /variants — sku 누락(@Valid 실패) → 200 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createVariant_validation_fail_rerenders() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .with(csrf())
                        .param("price", "15000")
                        .param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"))
                .andExpect(model().attributeExists("product"))
                .andExpect(model().attributeExists("options"))
                .andExpect(model().attributeExists("variants"))
                .andExpect(model().attributeExists("optionForm"))
                .andExpect(model().attributeExists("optionValueForm"))
                .andExpect(model().attributeExists("variantForm"));
    }

    @Test
    @DisplayName("POST /variants — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void createVariant_businessException_redirects_with_error() throws Exception {
        doThrow(new BusinessException("이미 사용 중인 SKU입니다."))
                .when(sellerProductVariantFacade)
                .createVariant(anyString(), anyBoolean(), anyLong(), anyString(), any(), anyInt(), anyBoolean(), anyList());

        mockMvc.perform(post(BASE_URL)
                        .with(csrf())
                        .param("sku", "SKU-DUP")
                        .param("price", "15000")
                        .param("stock", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    // ============================================================
    // POST /variants/{variantId} — variant 수정
    // ============================================================

    @Test
    @DisplayName("POST /variants/{variantId} — 성공 → 302 redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void updateVariant_success_redirects() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + VARIANT_ID)
                        .with(csrf())
                        .param("sku", "SKU-UPDATED")
                        .param("price", "20000")
                        .param("stock", "15")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    @Test
    @DisplayName("POST /variants/{variantId} — 성공 → facade.updateVariant 호출 검증")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void updateVariant_success_calls_facade() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + VARIANT_ID)
                        .with(csrf())
                        .param("sku", "SKU-UPDATED")
                        .param("price", "20000")
                        .param("stock", "15")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        verify(sellerProductVariantFacade).updateVariant(
                eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID), eq(VARIANT_ID),
                eq("SKU-UPDATED"), eq(new BigDecimal("20000")), eq(15), eq(true), anyList());
    }

    @Test
    @DisplayName("POST /variants/{variantId} — sku 누락(@Valid 실패) → 200 재렌더")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void updateVariant_validation_fail_rerenders() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + VARIANT_ID)
                        .with(csrf())
                        .param("price", "20000")
                        .param("stock", "15"))
                .andExpect(status().isOk())
                .andExpect(view().name("seller/product-variants"));
    }

    @Test
    @DisplayName("POST /variants/{variantId} — BusinessException → flashError redirect")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void updateVariant_businessException_redirects_with_error() throws Exception {
        doThrow(new BusinessException("이미 사용 중인 SKU입니다."))
                .when(sellerProductVariantFacade)
                .updateVariant(anyString(), anyBoolean(), anyLong(), anyLong(), anyString(), any(), anyInt(), anyBoolean(), anyList());

        mockMvc.perform(post(BASE_URL + "/" + VARIANT_ID)
                        .with(csrf())
                        .param("sku", "SKU-DUP")
                        .param("price", "20000")
                        .param("stock", "15"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BASE_URL));
    }

    // ============================================================
    // 소유권 예외 처리
    // ============================================================

    @Test
    @DisplayName("GET variants — 타인 상품 → 404(ProductAccessDeniedException)")
    @WithMockUser(username = "other@example.com", roles = "SELLER")
    void get_other_seller_product_returns_404() throws Exception {
        when(sellerProductVariantFacade.getManagementView(eq("other@example.com"), eq(false), eq(PRODUCT_ID)))
                .thenThrow(new ProductAccessDeniedException(PRODUCT_ID));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isNotFound());
    }
}
