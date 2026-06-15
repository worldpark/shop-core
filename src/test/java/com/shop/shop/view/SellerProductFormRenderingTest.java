package com.shop.shop.view;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.ProductFormView;
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
import com.shop.shop.product.spi.SellerProductFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 등록/수정 폼 HTML 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/seller/product-form.html)이
 * layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증한다.
 *
 * <p>SellerProductFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 * (원래 UserDirectory + CategoryRepository + ProductRepository를 직접 Mock하던 방식에서 전환)
 *
 * <p>검증 항목:
 * <ul>
 *   <li>공통 레이아웃(header·nav·footer) 포함</li>
 *   <li>CSRF 토큰 자동 주입 (_csrf 히든 필드)</li>
 *   <li>필드(categoryId/name/description/basePrice) 렌더링</li>
 *   <li>카테고리 옵션 렌더링 (미분류 + stub 카테고리)</li>
 *   <li>등록 화면: status 필드 비노출</li>
 *   <li>수정 화면: status 필드 노출 + statuses 옵션 렌더링</li>
 *   <li>검증 실패 시 에러 메시지 echo</li>
 *   <li>nav SELLER 상품 등록 링크 포함</li>
 * </ul>
 *
 * <p>패턴: LayoutRenderingTest 컨벤션 준수.
 * - @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")
 * - @Import(FakeRefreshTokenStore) + @MockitoBean JPA/DB 의존 격리
 * - SellerProductFacade @MockitoBean으로 product 도메인 내부 격리
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class SellerProductFormRenderingTest {

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

    @MockitoBean
    private SellerProductFacade sellerProductFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    private static final long SELLER_ID = 2L;
    private static final long PRODUCT_ID = 10L;
    private static final String SELLER_EMAIL = "seller@example.com";

    /** footer 프래그먼트 식별 마커 */
    static final String FOOTER_MARKER = "2026 shop-core. All rights reserved.";

    @BeforeEach
    void setUp() {
        // 기본 stub: 카테고리 비어있음, statuses 목록 제공
        when(sellerProductFacade.listCategories()).thenReturn(List.of());
        when(sellerProductFacade.productStatusNames())
                .thenReturn(List.of("DRAFT", "ON_SALE", "SOLD_OUT", "HIDDEN"));
    }

    // ============================================================
    // (P1) 등록 화면 공통 레이아웃 / CSRF / 필드
    // ============================================================

    @Test
    @DisplayName("(P1) GET /seller/products/new — 공통 레이아웃: header·nav·footer 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_includes_layout_fragments() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // header 마커
        assertThat(body).as("header(shop-core)가 포함되어야 함").contains("shop-core");
        // nav 마커 ('홈' 링크)
        assertThat(body).as("nav '홈' 마커가 포함되어야 함").contains("홈");
        // footer 마커
        assertThat(body).as("footer 마커가 포함되어야 함").contains(FOOTER_MARKER);
        // CSS 링크
        assertThat(body).as("/css/app.css 링크가 포함되어야 함").contains("/css/app.css");
    }

    @Test
    @DisplayName("(P2) GET /seller/products/new — CSRF 토큰 자동 주입 (_csrf 히든 필드)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_includes_csrf_token() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("_csrf 토큰이 폼에 자동 주입되어야 함 (th:action 자동)").contains("_csrf");
    }

    @Test
    @DisplayName("(P3) GET /seller/products/new — 폼 action이 /seller/products 여야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_action_is_seller_products() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("폼 action이 /seller/products 여야 함").contains("action=\"/seller/products\"");
        assertThat(body).as("폼 method가 post 여야 함").contains("method=\"post\"");
    }

    @Test
    @DisplayName("(P4) GET /seller/products/new — 필드(categoryId/name/description/basePrice) 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_renders_all_required_fields() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("categoryId 셀렉트가 렌더링되어야 함").contains("id=\"categoryId\"");
        assertThat(body).as("name 입력 필드가 렌더링되어야 함").contains("id=\"name\"");
        assertThat(body).as("description 텍스트에어리어가 렌더링되어야 함").contains("id=\"description\"");
        assertThat(body).as("basePrice 입력 필드가 렌더링되어야 함").contains("id=\"basePrice\"");
    }

    @Test
    @DisplayName("(P5) GET /seller/products/new — 미분류 옵션 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_category_select_includes_blank_option() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("카테고리 셀렉트에 미분류 빈 옵션이 있어야 함").contains("미분류");
    }

    @Test
    @DisplayName("(P6) GET /seller/products/new — 등록 화면에서 status 필드 비노출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_does_not_render_status_field() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("등록 화면에서 status 셀렉트(id=status)가 노출되면 안 됨")
                .doesNotContain("id=\"status\"");
    }

    // ============================================================
    // (P7) 수정 화면
    // ============================================================

    @Test
    @DisplayName("(P7) GET /seller/products/{id}/edit — 수정 화면에 status 필드 노출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void edit_form_renders_status_field() throws Exception {
        ProductFormView view = new ProductFormView(null, "상품", "설명", new BigDecimal("10000"), "DRAFT");
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID)))
                .thenReturn(view);

        String body = mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("수정 화면에 status 셀렉트(id=status)가 있어야 함").contains("id=\"status\"");
        // DRAFT 옵션 포함
        assertThat(body).as("DRAFT 옵션이 있어야 함").contains("DRAFT");
        // ON_SALE 옵션 포함
        assertThat(body).as("ON_SALE 옵션이 있어야 함").contains("ON_SALE");
    }

    @Test
    @DisplayName("(P8) GET /seller/products/{id}/edit — 폼 action이 /seller/products/{id} 여야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void edit_form_action_contains_product_id() throws Exception {
        ProductFormView view = new ProductFormView(null, "상품", "설명", new BigDecimal("10000"), "DRAFT");
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID)))
                .thenReturn(view);

        String body = mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("수정 폼 action이 /seller/products/{id}를 포함해야 함")
                .contains("action=\"/seller/products/" + PRODUCT_ID + "\"");
    }

    @Test
    @DisplayName("(P9) GET /seller/products/{id}/edit — CSRF 토큰 자동 주입")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void edit_form_includes_csrf_token() throws Exception {
        ProductFormView view = new ProductFormView(null, "상품", "설명", new BigDecimal("10000"), "DRAFT");
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID)))
                .thenReturn(view);

        String body = mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("수정 폼에 _csrf 토큰이 자동 주입되어야 함").contains("_csrf");
    }

    // ============================================================
    // (P10) 검증 실패 에러 메시지 echo
    // ============================================================

    @Test
    @DisplayName("(P10) POST /seller/products — name 누락 시 에러 메시지 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void post_validation_fail_renders_error_message() throws Exception {
        String body = mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("basePrice", "10000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 검증 실패 에러 메시지 (ProductForm @NotBlank "상품명은 필수입니다.")
        assertThat(body).as("name 검증 실패 에러 메시지가 렌더링되어야 함").contains("상품명은 필수입니다.");
    }

    @Test
    @DisplayName("(P11) POST /seller/products — 검증 실패 시 입력값(basePrice) 유지")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void post_validation_fail_preserves_input_values() throws Exception {
        String body = mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("basePrice", "5000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:field로 입력값(5000)이 유지되어야 함
        assertThat(body).as("basePrice 입력값(5000)이 유지되어야 함").contains("5000");
    }

    @Test
    @DisplayName("(P12) POST /seller/products — 검증 실패 시 categories 재주입 (미분류 옵션 유지)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void post_validation_fail_repopulates_categories() throws Exception {
        String body = mockMvc.perform(post("/seller/products")
                        .with(csrf())
                        .param("basePrice", "5000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("검증 실패 후 카테고리 미분류 옵션이 유지되어야 함").contains("미분류");
    }

    // ============================================================
    // (P13) nav SELLER 상품 등록 링크
    // ============================================================

    @Test
    @DisplayName("(P13) GET /seller/products/new — nav에 상품 등록 링크 포함 (SELLER)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_nav_contains_seller_product_link() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /seller/products/new 링크가 있어야 함").contains("/seller/products/new");
        assertThat(body).as("nav에 상품 등록 텍스트가 있어야 함").contains("상품 등록");
    }

    // ============================================================
    // (P14) 이미지 관리 링크 — 수정 화면에서 노출
    // ============================================================

    @Test
    @DisplayName("(P14) GET /seller/products/{id}/edit — 이미지 관리 링크(/seller/products/{id}/images) 노출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void edit_form_contains_images_link() throws Exception {
        ProductFormView view = new ProductFormView(null, "상품", "설명", new BigDecimal("10000"), "DRAFT");
        when(sellerProductFacade.getForEdit(eq(SELLER_EMAIL), eq(false), eq(PRODUCT_ID)))
                .thenReturn(view);

        String body = mockMvc.perform(get("/seller/products/" + PRODUCT_ID + "/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("수정 화면에 이미지 관리 링크가 있어야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/images");
        assertThat(body).as("수정 화면에 '이미지 관리' 텍스트가 있어야 함").contains("이미지 관리");
    }

    @Test
    @DisplayName("(P15) GET /seller/products/new — 등록 화면에서 이미지 관리 링크 미노출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void new_form_does_not_contain_images_link() throws Exception {
        String body = mockMvc.perform(get("/seller/products/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("등록 화면에서 이미지 관리 링크는 없어야 함")
                .doesNotContain("/images");
    }
}
