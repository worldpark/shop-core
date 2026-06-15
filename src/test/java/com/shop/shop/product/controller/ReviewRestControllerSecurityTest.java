package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.DuplicateReviewException;
import com.shop.shop.common.exception.ReviewNotFoundException;
import com.shop.shop.common.exception.ReviewNotPurchasedException;
import com.shop.shop.common.exception.ReviewTargetNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.dto.ProductReviewSummaryResponse;
import com.shop.shop.product.dto.ReviewCreateRequest;
import com.shop.shop.product.dto.ReviewResponse;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.product.service.ReviewServiceResponse;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReviewRestController + SecurityConfig REST 체인 MockMvc 보안 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>POST/PATCH/DELETE /api/v1/reviews/** — 비로그인 401, 비CONSUMER 403, CONSUMER 통과</li>
 *   <li>GET /api/v1/products/{id}/reviews — 비로그인 200(공개)</li>
 *   <li>매처 우선순위: /api/v1/products/{id}/reviews GET 200 (permitAll) vs /api/v1/reviews/{id} CONSUMER</li>
 *   <li>타인 리뷰 수정·삭제 → 404 존재 은닉</li>
 *   <li>rating 0/6 → 400</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class ReviewRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    // Service mock
    @MockitoBean
    private ReviewServiceResponse reviewServiceResponse;

    // Repository mocks (컨텍스트 로드 — verification-gate-rule §4)
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
    private ReviewRepository reviewRepository;
    @MockitoBean
    private CartRepository cartRepository;
    @MockitoBean
    private CartItemRepository cartItemRepository;
    @MockitoBean
    private OrderRepository orderRepository;
    @MockitoBean
    private ShipmentRepository shipmentRepository;
    @MockitoBean
    private PaymentRepository paymentRepository;
    @MockitoBean
    private InventoryStockRepository inventoryStockRepository;
    @MockitoBean
    private CouponRepository couponRepository;
    @MockitoBean
    private UserCouponRepository userCouponRepository;
    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    private String adminToken;
    private String sellerToken;
    private String consumerToken;
    private String noRoleToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
        noRoleToken = jwtTokenProvider.createAccess(4L, "norole@example.com", List.of());
    }

    // =========================================================
    // POST /api/v1/reviews — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/reviews — 비인증 → 401")
    void createReview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/reviews — ROLE 없음 → 403")
    void createReview_noRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + noRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/reviews — CONSUMER → 201")
    void createReview_consumer_returns201() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenReturn(buildReviewResponse());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/reviews — SELLER → 201 (역할 계층)")
    void createReview_seller_returns201() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenReturn(buildReviewResponse());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/reviews — ADMIN → 201 (역할 계층)")
    void createReview_admin_returns201() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenReturn(buildReviewResponse());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isCreated());
    }

    // =========================================================
    // PATCH /api/v1/reviews/{id} — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("PATCH /api/v1/reviews/{id} — 비인증 → 401")
    void updateReview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /api/v1/reviews/{id} — CONSUMER → 200")
    void updateReview_consumer_returns200() throws Exception {
        when(reviewServiceResponse.update(any(), anyLong(), any())).thenReturn(buildReviewResponse());

        mockMvc.perform(patch("/api/v1/reviews/1")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/reviews/{id} — 타인 리뷰 → 404 존재 은닉")
    void updateReview_otherUser_returns404() throws Exception {
        when(reviewServiceResponse.update(any(), anyLong(), any())).thenThrow(new ReviewNotFoundException());

        mockMvc.perform(patch("/api/v1/reviews/1")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================
    // DELETE /api/v1/reviews/{id} — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("DELETE /api/v1/reviews/{id} — 비인증 → 401")
    void deleteReview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/v1/reviews/{id} — CONSUMER → 204")
    void deleteReview_consumer_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/reviews/{id} — 타인 리뷰 → 404")
    void deleteReview_otherUser_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new ReviewNotFoundException())
                .when(reviewServiceResponse).delete(any(), anyLong());

        mockMvc.perform(delete("/api/v1/reviews/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound());
    }

    // =========================================================
    // GET /api/v1/products/{id}/reviews — 공개 조회
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/products/{id}/reviews — 비로그인 200(공개)")
    void getProductReviews_unauthenticated_returns200() throws Exception {
        when(reviewServiceResponse.getProductReviews(anyLong(), anyInt(), anyInt()))
                .thenReturn(buildSummaryResponse());

        mockMvc.perform(get("/api/v1/products/1/reviews"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매처 우선순위 — /api/v1/products/*/reviews 는 permitAll")
    void getProductReviews_matcherPriority_permitAll() throws Exception {
        when(reviewServiceResponse.getProductReviews(anyLong(), anyInt(), anyInt()))
                .thenReturn(buildSummaryResponse());

        mockMvc.perform(get("/api/v1/products/999/reviews"))
                .andExpect(status().isOk());
    }

    // =========================================================
    // 검증 실패 (400)
    // =========================================================

    @Test
    @DisplayName("rating=0 → 400")
    void createReview_ratingZero_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 0, "내용")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rating=6 → 400")
    void createReview_ratingSix_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 6, "내용")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("타인 order_item 작성 → 404")
    void createReview_otherOrderItem_returns404() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenThrow(new ReviewTargetNotFoundException());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("미배송 order_item 작성 → 400")
    void createReview_notDelivered_returns400() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenThrow(new ReviewNotPurchasedException());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("중복 리뷰 → 409")
    void createReview_duplicate_returns409() throws Exception {
        when(reviewServiceResponse.create(any(), any())).thenThrow(new DuplicateReviewException());

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateJson(1L, 4, "좋아요")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private String buildCreateJson(long orderItemId, int rating, String content) {
        return String.format("{\"orderItemId\":%d,\"rating\":%d,\"content\":\"%s\"}",
                orderItemId, rating, content);
    }

    private ReviewResponse buildReviewResponse() {
        return new ReviewResponse(1L, 100L, "al***", 4, "좋아요",
                Instant.now(), Instant.now());
    }

    private ProductReviewSummaryResponse buildSummaryResponse() {
        return new ProductReviewSummaryResponse(4.5, 2, 0, 10, 2, 1, List.of());
    }
}
