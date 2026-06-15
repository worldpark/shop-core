package com.shop.shop.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.common.exception.CouponConflictException;
import com.shop.shop.common.exception.CouponNotFoundException;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.dto.CouponClaimRequest;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.service.CouponServiceResponse;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CouponRestController + AdminCouponRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>/api/v1/coupons/** 비로그인 401, CONSUMER 200/201, SELLER/ADMIN 통과(역할 계층)</li>
 *   <li>admin POST /api/v1/admin/coupons: ADMIN 201, CONSUMER 403, 비로그인 401</li>
 *   <li>미보유 userCoupon → 404, 이미 사용 → 409</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class CouponRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    // Service mock
    @MockitoBean
    private CouponServiceResponse couponServiceResponse;

    // Repository mocks (컨텍스트 로드)
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
    private com.shop.shop.product.repository.ReviewRepository reviewRepository;

    @MockitoBean
    private com.shop.shop.order.adapter.OrderItemQueryRepository orderItemQueryRepository;

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
    // POST /api/v1/coupons — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/coupons — 비인증 → 401")
    void claimCoupon_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/coupons — ROLE 없는 인증 → 403")
    void claimCoupon_noRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + noRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/coupons — CONSUMER → 201")
    void claimCoupon_consumer_returns201() throws Exception {
        when(couponServiceResponse.claim(any(), any())).thenReturn(buildUserCouponResponse());

        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/coupons — SELLER → 201 (역할 계층 함의)")
    void claimCoupon_seller_returns201() throws Exception {
        when(couponServiceResponse.claim(any(), any())).thenReturn(buildUserCouponResponse());

        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/coupons — ADMIN → 201 (역할 계층 함의)")
    void claimCoupon_admin_returns201() throws Exception {
        when(couponServiceResponse.claim(any(), any())).thenReturn(buildUserCouponResponse());

        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isCreated());
    }

    // =========================================================
    // GET /api/v1/coupons — 인증/역할별 접근
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/coupons — 비인증 → 401")
    void getMyCoupons_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/coupons"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/coupons — CONSUMER → 200")
    void getMyCoupons_consumer_returns200() throws Exception {
        when(couponServiceResponse.getMyCoupons(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/coupons")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    // =========================================================
    // GET /api/v1/coupons/applicable
    // =========================================================

    @Test
    @DisplayName("GET /api/v1/coupons/applicable — 비인증 → 401")
    void getApplicable_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/applicable"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/coupons/applicable — CONSUMER → 200")
    void getApplicable_consumer_returns200() throws Exception {
        when(couponServiceResponse.getApplicable(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/coupons/applicable")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    // =========================================================
    // POST /api/v1/admin/coupons — admin 전용
    // =========================================================

    @Test
    @DisplayName("POST /api/v1/admin/coupons — 비인증 → 401")
    void createAdminCoupon_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildAdminCouponJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/admin/coupons — CONSUMER → 403")
    void createAdminCoupon_consumer_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildAdminCouponJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/coupons — ADMIN → 201")
    void createAdminCoupon_admin_returns201() throws Exception {
        when(couponServiceResponse.createDefinition(any())).thenReturn(buildAdminCouponResponse());

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildAdminCouponJson()))
                .andExpect(status().isCreated());
    }

    // =========================================================
    // 에러 응답
    // =========================================================

    @Test
    @DisplayName("미보유 쿠폰 claim → 404 ErrorResponse")
    void claimCoupon_notFound_returns404() throws Exception {
        when(couponServiceResponse.claim(any(), any())).thenThrow(new CouponNotFoundException());

        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOTEXIST\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("이미 사용된 쿠폰 → 409 ErrorResponse")
    void claimCoupon_alreadyUsed_returns409() throws Exception {
        when(couponServiceResponse.claim(any(), any())).thenThrow(new CouponConflictException("이미 사용된 쿠폰입니다."));

        mockMvc.perform(post("/api/v1/coupons")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private UserCouponResponse buildUserCouponResponse() {
        return new UserCouponResponse(
                100L, 99L, "SAVE10", "10% 할인", "percent",
                new BigDecimal("10"), BigDecimal.ZERO, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                false, null, false
        );
    }

    private AdminCouponResponse buildAdminCouponResponse() {
        return new AdminCouponResponse(
                1L, "SAVE10", "10% 할인", "percent",
                new BigDecimal("10"), BigDecimal.ZERO, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                null, 0, true
        );
    }

    private String buildAdminCouponJson() {
        return """
                {
                    "code": "SAVE10",
                    "name": "10% 할인",
                    "discountType": "percent",
                    "value": 10,
                    "startsAt": "2026-01-01T00:00:00Z",
                    "endsAt": "2027-01-01T00:00:00Z"
                }
                """;
    }
}
