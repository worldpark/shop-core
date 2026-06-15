package com.shop.shop.web.review;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.dto.ReviewResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * ReviewViewController + PublicProductViewController(리뷰 섹션) MockMvc 통합 테스트.
 *
 * <p>ReviewFacade(@MockitoBean)를 통해 facade 배선·모델 키·view name·redirect·flash 키를 검증.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>(a) POST /reviews 성공 → redirect:/products/{productId}?review + flash 키 flashSuccess</li>
 *   <li>(a) POST /reviews/{id}/edit 성공 → redirect:/products/{productId}?review + flash 키 flashSuccess</li>
 *   <li>(a) POST /reviews/{id}/delete 성공 → redirect:/products/{productId} + flash 키 flashSuccess</li>
 *   <li>(b) POST /reviews 검증 실패 → review/form 재렌더 + fieldErrors + 입력 보존</li>
 *   <li>(c) GET /products/{id} → 모델 키 productReviews/reviewSummary 존재 + 마스킹 표시명 비노출</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class ReviewViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    private ReviewRepository reviewRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewFacade reviewFacade;

    @MockitoBean
    private PublicProductFacade publicProductFacade;

    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    private static final String USER_EMAIL = "consumer@example.com";
    private static final long PRODUCT_ID = 10L;
    private static final long REVIEW_ID = 99L;
    private static final long ORDER_ITEM_ID = 7L;

    @BeforeEach
    void setUp() {
        // create/edit/delete 기본 stub
        when(reviewFacade.create(anyString(), anyLong(), anyInt(), anyString()))
                .thenReturn(PRODUCT_ID);
        when(reviewFacade.edit(anyString(), anyLong(), anyInt(), anyString()))
                .thenReturn(PRODUCT_ID);
        when(reviewFacade.delete(anyString(), anyLong()))
                .thenReturn(PRODUCT_ID);

        // 상품 상세 리뷰 stub
        when(reviewFacade.getProductReviews(eq(PRODUCT_ID), anyInt(), anyInt()))
                .thenReturn(sampleReviewSummary());

        // PublicProductFacade stub (GET /products/{id} 렌더 위해)
        when(publicProductFacade.getProductDetail(PRODUCT_ID))
                .thenReturn(sampleProductDetail());
        when(publicProductFacade.listCategories()).thenReturn(List.of());
    }

    // ============================================================
    // (a) POST /reviews — 성공 redirect + flashSuccess 키
    // ============================================================

    @Test
    @DisplayName("POST /reviews — 성공 → redirect:/products/{productId}?review")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_success_redirectsToProductDetail() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("rating", "5")
                        .param("content", "좋아요"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/products/" + PRODUCT_ID + "?review"));
    }

    @Test
    @DisplayName("POST /reviews — 성공 flash 키는 fragment 표준 flashSuccess")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_success_flashKeyIsFlashSuccess() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("rating", "5")
                        .param("content", "좋아요"))
                .andExpect(flash().attribute("flashSuccess", "리뷰가 작성되었습니다."));
    }

    @Test
    @DisplayName("POST /reviews/{id}/edit — 성공 flash 키는 fragment 표준 flashSuccess")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void edit_success_flashKeyIsFlashSuccess() throws Exception {
        mockMvc.perform(post("/reviews/" + REVIEW_ID + "/edit")
                        .with(csrf())
                        .param("rating", "4")
                        .param("content", "수정된 내용"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/products/" + PRODUCT_ID + "?review"))
                .andExpect(flash().attribute("flashSuccess", "리뷰가 수정되었습니다."));
    }

    @Test
    @DisplayName("POST /reviews/{id}/delete — 성공 flash 키는 fragment 표준 flashSuccess")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void delete_success_flashKeyIsFlashSuccess() throws Exception {
        mockMvc.perform(post("/reviews/" + REVIEW_ID + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/products/" + PRODUCT_ID))
                .andExpect(flash().attribute("flashSuccess", "리뷰가 삭제되었습니다."));
    }

    // ============================================================
    // (b) POST /reviews — 검증 실패 → form 재렌더 + fieldErrors + 입력 보존
    // ============================================================

    @Test
    @DisplayName("POST /reviews — rating 누락 → review/form 재렌더")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_missingRating_rerendersForm() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("content", "내용만 있음"))
                .andExpect(status().isOk())
                .andExpect(view().name("review/form"));
    }

    @Test
    @DisplayName("POST /reviews — rating 누락 → reviewForm fieldError 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_missingRating_hasFieldErrors() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("content", "내용만 있음"))
                .andExpect(model().attributeHasFieldErrors("reviewForm", "rating"));
    }

    @Test
    @DisplayName("POST /reviews — rating 범위 초과(6) → reviewForm fieldError 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_ratingOutOfRange_hasFieldErrors() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("rating", "6")
                        .param("content", "범위 초과"))
                .andExpect(status().isOk())
                .andExpect(view().name("review/form"))
                .andExpect(model().attributeHasFieldErrors("reviewForm", "rating"));
    }

    @Test
    @DisplayName("POST /reviews — 검증 실패 시 입력값(content) 모델에 보존")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_validationFail_preservesInput() throws Exception {
        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("content", "입력 보존 확인"))
                .andExpect(status().isOk())
                .andExpect(view().name("review/form"))
                .andExpect(model().attributeExists("reviewForm"));
    }

    @Test
    @DisplayName("POST /reviews — BusinessException → review/form 재렌더 + errorMessage 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void create_businessException_rerendersFormWithError() throws Exception {
        when(reviewFacade.create(anyString(), anyLong(), anyInt(), anyString()))
                .thenThrow(new BusinessException("이미 리뷰를 작성하셨습니다."));

        mockMvc.perform(post("/reviews")
                        .with(csrf())
                        .param("orderItemId", String.valueOf(ORDER_ITEM_ID))
                        .param("rating", "5")
                        .param("content", "중복 시도"))
                .andExpect(status().isOk())
                .andExpect(view().name("review/form"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    // ============================================================
    // (c) GET /products/{id} — 모델 키 productReviews/reviewSummary + 마스킹 단언
    // ============================================================

    @Test
    @DisplayName("GET /products/{id} — 모델에 productReviews 키 존재")
    void getProductDetail_modelContainsProductReviews() throws Exception {
        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("productReviews"));
    }

    @Test
    @DisplayName("GET /products/{id} — 모델에 reviewSummary 키 존재")
    void getProductDetail_modelContainsReviewSummary() throws Exception {
        mockMvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("reviewSummary"));
    }

    @Test
    @DisplayName("GET /products/{id} — 리뷰 작성자 표시명이 마스킹(email 비포함) 형태로 반환됨")
    @SuppressWarnings("unchecked")
    void getProductDetail_reviewAuthorIsMasked_notEmail() throws Exception {
        org.springframework.test.web.servlet.MvcResult result =
                mockMvc.perform(get("/products/" + PRODUCT_ID))
                        .andExpect(status().isOk())
                        .andReturn();

        java.util.List<ReviewResponse> reviews =
                (java.util.List<ReviewResponse>) result.getModelAndView().getModel().get("productReviews");

        org.junit.jupiter.api.Assertions.assertFalse(reviews.isEmpty(), "productReviews는 비어있지 않아야 한다");
        reviews.forEach(r ->
                org.junit.jupiter.api.Assertions.assertFalse(
                        r.authorDisplayName().contains("@"),
                        "작성자 표시명에 email(@)이 포함되면 안 된다: " + r.authorDisplayName()
                )
        );
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private ProductReviewSummaryResponse sampleReviewSummary() {
        ReviewResponse review = new ReviewResponse(
                REVIEW_ID,
                PRODUCT_ID,
                "김*동",        // 마스킹된 표시명 — email(@) 미포함
                5,
                "정말 좋은 상품입니다.",
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T10:00:00Z")
        );
        return new ProductReviewSummaryResponse(
                4.8,
                1L,
                0,
                10,
                1L,
                1,
                List.of(review)
        );
    }

    private com.shop.shop.product.dto.PublicProductDetailResponse sampleProductDetail() {
        return new com.shop.shop.product.dto.PublicProductDetailResponse(
                PRODUCT_ID, "테스트 상품", "상품 설명",
                new java.math.BigDecimal("10000"), false,
                new com.shop.shop.product.dto.PublicCategoryResponse(1L, "전자기기"),
                List.of(), List.of(), List.of()
        );
    }
}
