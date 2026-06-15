package com.shop.shop.view;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.dto.PublicCategoryResponse;
import com.shop.shop.product.dto.PublicProductDetailResponse;
import com.shop.shop.product.dto.PublicProductImageResponse;
import com.shop.shop.product.dto.PublicProductOptionResponse;
import com.shop.shop.product.dto.PublicProductVariantResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.spi.PublicProductFacade;
import com.shop.shop.product.spi.ReviewFacade;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 상품 상세 화면 — 장바구니 담기 폼 렌더링 테스트 (Task 014).
 *
 * <p>templates/product/detail.html에 추가된 담기 폼 영역을 검증한다.
 *
 * <p>PublicProductFacade(@MockitoBean)를 통해 facade 배선 동작을 격리한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>인증 사용자: 담기 폼(variantId/quantity/action POST /cart/items) 렌더링</li>
 *   <li>비인증 사용자: 로그인 안내("로그인 후 담기 가능") 표시</li>
 *   <li>soldOut 상품: 담기 폼 숨김 + 구매 불가 안내</li>
 *   <li>variants 없음: 담기 폼 숨김</li>
 *   <li>available=false variant: 선택 불가 표시</li>
 *   <li>폼 action이 /cart/items 임</li>
 *   <li>폼 필드: variantId(radio), quantity(number)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class ProductDetailAddToCartRenderingTest {

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
    private PublicProductFacade publicProductFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private com.shop.shop.product.repository.ReviewRepository reviewRepository;

    @MockitoBean
    private ReviewFacade reviewFacade;

    private static final long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() {
        when(publicProductFacade.getProductDetail(PRODUCT_ID))
                .thenReturn(sampleDetailResponse(PRODUCT_ID, false));
        when(reviewFacade.getProductReviews(anyLong(), anyInt(), anyInt()))
                .thenReturn(new ProductReviewSummaryResponse(null, 0L, 0, 10, 0L, 0, java.util.List.of()));
    }

    // ============================================================
    // (AC1) 비인증 → 로그인 안내
    // ============================================================

    @Test
    @DisplayName("(AC1) GET /products/{id} — 비인증 → 로그인 안내 표시")
    void getProductDetail_unauthenticated_showsLoginNotice() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("로그인 안내 텍스트가 있어야 함").contains("로그인 후 담기 가능");
    }

    @Test
    @DisplayName("(AC1) GET /products/{id} — 비인증 → /login 링크 표시")
    void getProductDetail_unauthenticated_showsLoginLink() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("/login 링크가 있어야 함").contains("/login");
    }

    // ============================================================
    // (AC2) 인증 → 담기 폼 렌더링
    // ============================================================

    @Test
    @DisplayName("(AC2) GET /products/{id} — CONSUMER 인증 → 담기 폼(action=/cart/items) 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_authenticated_showsAddToCartForm() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("담기 폼 action이 /cart/items 이어야 함")
                .contains("/cart/items");
    }

    @Test
    @DisplayName("(AC2) GET /products/{id} — 담기 폼에 variantId 라디오 버튼 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_authenticated_formContainsVariantIdField() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("variantId 라디오 버튼이 있어야 함")
                .contains("name=\"variantId\"");
    }

    @Test
    @DisplayName("(AC2) GET /products/{id} — 담기 폼에 quantity 입력 필드 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_authenticated_formContainsQuantityField() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("quantity 필드가 있어야 함").contains("name=\"quantity\"");
    }

    @Test
    @DisplayName("(AC2) GET /products/{id} — 담기 폼 method=post")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_authenticated_formIsPost() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("폼 method=post가 있어야 함").containsIgnoringCase("method=\"post\"");
    }

    // ============================================================
    // (AC3) soldOut → 폼 숨김
    // ============================================================

    @Test
    @DisplayName("(AC3) GET /products/{id} — soldOut=true → 구매 불가 안내 표시, 담기 폼 숨김")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_soldOut_hideAddToCartForm() throws Exception {
        when(publicProductFacade.getProductDetail(PRODUCT_ID))
                .thenReturn(sampleDetailResponse(PRODUCT_ID, true));

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("구매 불가 안내가 있어야 함").contains("구매 가능한 옵션이 없습니다");
    }

    // ============================================================
    // (AC4) variants 없음 → 폼 숨김
    // ============================================================

    @Test
    @DisplayName("(AC4) GET /products/{id} — variants 없음 → 폼 숨김")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_noVariants_hideAddToCartForm() throws Exception {
        PublicProductDetailResponse noVariantDetail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("10000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(new PublicProductImageResponse(100L, "http://localhost/img.jpg", 0, true)),
                List.of(), List.of()
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(noVariantDetail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("구매 불가 안내가 있어야 함").contains("구매 가능한 옵션이 없습니다");
    }

    // ============================================================
    // (AC5) available=false variant → 선택 불가 표시
    // ============================================================

    @Test
    @DisplayName("(AC5) GET /products/{id} — available=false variant → disabled 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_unavailableVariant_rendersDisabled() throws Exception {
        PublicProductVariantResponse unavailableVariant = new PublicProductVariantResponse(
                300L, new BigDecimal("12000"), List.of(), false);
        PublicProductVariantResponse availableVariant = new PublicProductVariantResponse(
                301L, new BigDecimal("15000"), List.of(), true);
        PublicProductDetailResponse detail = new PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "설명",
                new BigDecimal("12000"), false,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(new PublicProductImageResponse(100L, "http://localhost/img.jpg", 0, true)),
                List.of(), List.of(unavailableVariant, availableVariant)
        );
        when(publicProductFacade.getProductDetail(PRODUCT_ID)).thenReturn(detail);

        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("disabled 라디오가 있어야 함").contains("disabled");
    }

    // ============================================================
    // (AC6) nav에 /cart 링크 (CONSUMER)
    // ============================================================

    @Test
    @DisplayName("(AC6) GET /products/{id} — CONSUMER 인증 → nav에 /cart 링크 노출")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getProductDetail_consumerAuth_navContainsCartLink() throws Exception {
        String body = mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /cart 링크가 있어야 함").contains("/cart");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private PublicProductDetailResponse sampleDetailResponse(long productId, boolean soldOut) {
        PublicProductImageResponse image = new PublicProductImageResponse(
                100L, "http://localhost:8080/assets/products/1/img.jpg", 0, true);
        PublicProductOptionResponse option = new PublicProductOptionResponse(
                50L, "색상", List.of());
        PublicProductVariantResponse variant = new PublicProductVariantResponse(
                300L, new BigDecimal("10000"), List.of(), !soldOut);
        return new PublicProductDetailResponse(
                productId, "테스트 상품", "상세 설명",
                new BigDecimal("10000"), soldOut,
                new PublicCategoryResponse(2L, "전자기기"),
                List.of(image), List.of(option),
                soldOut ? List.of() : List.of(variant)
        );
    }
}
