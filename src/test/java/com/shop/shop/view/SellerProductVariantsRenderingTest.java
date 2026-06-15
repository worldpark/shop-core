package com.shop.shop.view;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 옵션/Variant 관리 화면 HTML 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿(templates/seller/product-variants.html)이
 * layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증한다.
 *
 * <p>SellerProductVariantFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>공통 레이아웃(header·nav·footer) 포함</li>
 *   <li>CSRF 토큰 자동 주입 (_csrf 히든 필드)</li>
 *   <li>옵션 생성 폼 필드(name) 렌더링</li>
 *   <li>옵션값 생성 폼 필드(value) 렌더링</li>
 *   <li>variant 생성 폼 필드(sku/price/stock/active/optionValueIds) 렌더링</li>
 *   <li>옵션/옵션값 목록 표시</li>
 *   <li>variant 목록 테이블 표시</li>
 *   <li>flashSuccess/flashError 메시지 영역 포함</li>
 *   <li>검증 실패 시 에러 메시지 echo</li>
 * </ul>
 *
 * <p>패턴: SellerProductFormRenderingTest 컨벤션 준수.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerProductVariantsRenderingTest {

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
    private SellerProductVariantFacade sellerProductVariantFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    private static final long PRODUCT_ID = 10L;
    private static final long OPTION_ID = 20L;
    private static final long VARIANT_ID = 30L;
    private static final String SELLER_EMAIL = "seller@example.com";
    private static final String BASE_URL = "/seller/products/" + PRODUCT_ID + "/variants";

    /** footer 프래그먼트 식별 마커 */
    static final String FOOTER_MARKER = "2026 shop-core. All rights reserved.";

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
        VariantManagementView stubView = new VariantManagementView(productRef, options, variants);

        when(sellerProductVariantFacade.getManagementView(anyString(), anyBoolean(), anyLong()))
                .thenReturn(stubView);
    }

    // ============================================================
    // (R1) 공통 레이아웃
    // ============================================================

    @Test
    @DisplayName("(R1) GET /variants — 공통 레이아웃: header·nav·footer 포함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_includes_layout_fragments() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("header(shop-core)가 포함되어야 함").contains("shop-core");
        assertThat(body).as("nav '홈' 마커가 포함되어야 함").contains("홈");
        assertThat(body).as("footer 마커가 포함되어야 함").contains(FOOTER_MARKER);
        assertThat(body).as("/css/app.css 링크가 포함되어야 함").contains("/css/app.css");
    }

    // ============================================================
    // (R2) CSRF 토큰
    // ============================================================

    @Test
    @DisplayName("(R2) GET /variants — CSRF 토큰 자동 주입 (_csrf 히든 필드)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_includes_csrf_token() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("_csrf 토큰이 폼에 자동 주입되어야 함").contains("_csrf");
    }

    // ============================================================
    // (R3) 옵션 생성 폼 필드
    // ============================================================

    @Test
    @DisplayName("(R3) GET /variants — 옵션 생성 폼(name 필드, POST action) 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_option_create_form() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("optionName 필드가 있어야 함").contains("id=\"optionName\"");
        assertThat(body).as("옵션 생성 action이 /seller/products/{productId}/options 여야 함")
                .contains("action=\"/seller/products/" + PRODUCT_ID + "/options\"");
    }

    // ============================================================
    // (R4) 옵션값 생성 폼 필드
    // ============================================================

    @Test
    @DisplayName("(R4) GET /variants — 옵션값 생성 폼(value 필드) 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_option_value_form() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("옵션값 생성 action이 .../options/{optionId}/values 여야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/options/" + OPTION_ID + "/values");
    }

    // ============================================================
    // (R5) variant 생성 폼 필드
    // ============================================================

    @Test
    @DisplayName("(R5) GET /variants — variant 생성 폼 필드(sku/price/stock/active) 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_variant_create_form_fields() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variantSku 필드가 있어야 함").contains("id=\"variantSku\"");
        assertThat(body).as("variantPrice 필드가 있어야 함").contains("id=\"variantPrice\"");
        assertThat(body).as("variantStock 필드가 있어야 함").contains("id=\"variantStock\"");
        assertThat(body).as("variantActive 체크박스가 있어야 함").contains("id=\"variantActive\"");
    }

    @Test
    @DisplayName("(R5b) GET /variants — variant 생성 폼의 optionValueIds 체크박스 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_option_value_checkboxes() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("optionValueIds 체크박스(name=optionValueIds)가 있어야 함")
                .contains("name=\"optionValueIds\"");
    }

    // ============================================================
    // (R6) 옵션/옵션값 목록 표시
    // ============================================================

    @Test
    @DisplayName("(R6) GET /variants — 옵션명 '색상' 과 옵션값 '빨강','파랑' 이 렌더링되어야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_option_and_values_list() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("옵션명 '색상'이 렌더링되어야 함").contains("색상");
        assertThat(body).as("옵션값 '빨강'이 렌더링되어야 함").contains("빨강");
        assertThat(body).as("옵션값 '파랑'이 렌더링되어야 함").contains("파랑");
    }

    // ============================================================
    // (R7) variant 목록 테이블
    // ============================================================

    @Test
    @DisplayName("(R7) GET /variants — variant SKU 'SKU-001'이 테이블에 렌더링되어야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_variant_table() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variant SKU 'SKU-001'이 표시되어야 함").contains("SKU-001");
        assertThat(body).as("variant 가격 '10000'이 표시되어야 함").contains("10000");
    }

    // ============================================================
    // (R8) 상품명 헤딩
    // ============================================================

    @Test
    @DisplayName("(R8) GET /variants — 상품명 '테스트 상품'이 헤딩에 포함되어야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_renders_product_name_heading() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명 '테스트 상품'이 헤딩에 포함되어야 함").contains("테스트 상품");
    }

    // ============================================================
    // (R9) 검증 실패 에러 메시지 echo
    // ============================================================

    @Test
    @DisplayName("(R9) POST /options — name 누락 시 에러 메시지 '옵션명은 필수입니다.' 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void option_create_validation_fail_renders_error_message() throws Exception {
        String body = mockMvc.perform(post(BASE_URL + "/options")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("옵션명 검증 실패 에러 메시지가 렌더링되어야 함").contains("옵션명은 필수입니다.");
    }

    @Test
    @DisplayName("(R10) POST /variants — sku 누락 시 에러 메시지 'SKU는 필수입니다.' 렌더링")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variant_create_validation_fail_renders_error_message() throws Exception {
        String body = mockMvc.perform(post(BASE_URL)
                        .with(csrf())
                        .param("price", "10000")
                        .param("stock", "5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("SKU 검증 실패 에러 메시지가 렌더링되어야 함").contains("SKU는 필수입니다.");
    }

    // ============================================================
    // (R11) nav SELLER 상품 등록 링크
    // ============================================================

    @Test
    @DisplayName("(R11) GET /variants — nav에 상품 등록 링크 포함 (SELLER)")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_nav_contains_seller_product_link() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /seller/products/new 링크가 있어야 함").contains("/seller/products/new");
    }

    // ============================================================
    // (R12) variant 생성 폼 action
    // ============================================================

    @Test
    @DisplayName("(R12) GET /variants — variant 생성 폼 action이 /seller/products/{productId}/variants 여야 함")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_create_form_action_is_correct() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variant 생성 폼 action이 올바른 URL이어야 함")
                .contains("action=\"/seller/products/" + PRODUCT_ID + "/variants\"");
    }

    // ============================================================
    // (R13) 이미지 관리 링크
    // ============================================================

    @Test
    @DisplayName("(R13) GET /variants — 이미지 관리 링크(/seller/products/{productId}/images) 노출")
    @WithMockUser(username = SELLER_EMAIL, roles = "SELLER")
    void variants_contains_images_link() throws Exception {
        String body = mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variants 화면에 이미지 관리 링크가 있어야 함")
                .contains("/seller/products/" + PRODUCT_ID + "/images");
        assertThat(body).as("variants 화면에 '이미지 관리' 텍스트가 있어야 함").contains("이미지 관리");
    }
}
