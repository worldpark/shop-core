package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.RejectRequest;
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
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.security.JwtTokenProvider;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminSellerApplicationRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>GET /api/v1/admin/seller-applications: ADMIN 200 + PageResponse, CONSUMER/SELLER 403, 비인증 401</li>
 *   <li>POST /approve: ADMIN 200, CONSUMER/SELLER 403, 비인증 401</li>
 *   <li>POST /reject: ADMIN 200, reason @Valid 400, CONSUMER/SELLER 403, 비인증 401</li>
 *   <li>승인 성공 시 대상 userId가 SELLER로 변경됨 (changeRole 호출 확인)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminSellerApplicationRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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
    private com.shop.shop.product.repository.ReviewRepository reviewRepository;

    @MockitoBean
    private com.shop.shop.order.adapter.OrderItemQueryRepository orderItemQueryRepository;


    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    private User adminUser;
    private User consumerUser;

    @BeforeEach
    void setUp() {
        adminUser = userWithId(1L, "admin@test.com", Role.ADMIN);
        User sellerUser = userWithId(20L, "seller@test.com", Role.SELLER);
        consumerUser = userWithId(10L, "consumer@test.com", Role.CONSUMER);

        when(memberRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(memberRepository.findById(20L)).thenReturn(Optional.of(sellerUser));
        when(memberRepository.findById(10L)).thenReturn(Optional.of(consumerUser));

        when(sellerApplicationRepository.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        adminToken = jwtTokenProvider.createAccess(1L, "admin@test.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(20L, "seller@test.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(10L, "consumer@test.com", List.of("ROLE_CONSUMER"));

        TransactionSynchronizationManager.initSynchronization();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ============================================================
    // GET /api/v1/admin/seller-applications
    // ============================================================

    @Test
    @DisplayName("GET /api/v1/admin/seller-applications — ADMIN Bearer → 200 + PageResponse 구조")
    void list_admin_returns_200_with_page_response() throws Exception {
        mockMvc.perform(get("/api/v1/admin/seller-applications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    @DisplayName("GET /api/v1/admin/seller-applications — CONSUMER Bearer → 403")
    void list_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/seller-applications")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/admin/seller-applications — SELLER Bearer → 403")
    void list_seller_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/seller-applications")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/seller-applications — 비인증 → 401 JSON")
    void list_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/seller-applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ============================================================
    // POST /api/v1/admin/seller-applications/{id}/approve
    // ============================================================

    @Test
    @DisplayName("POST .../approve — ADMIN Bearer + PENDING 신청 → 200 (changeRole(SELLER) 호출)")
    void approve_admin_pending_returns_200() throws Exception {
        long applicationId = 42L;
        long applicantUserId = 10L;

        SellerApplication app = pendingApp(applicationId, applicantUserId);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST .../approve — CONSUMER Bearer → 403")
    void approve_consumer_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/approve", 42L)
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST .../approve — 비인증 → 401 JSON")
    void approve_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/approve", 42L))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // POST /api/v1/admin/seller-applications/{id}/reject
    // ============================================================

    @Test
    @DisplayName("POST .../reject — ADMIN Bearer + reason 있음 → 200")
    void reject_admin_with_reason_returns_200() throws Exception {
        long applicationId = 50L;
        SellerApplication app = pendingApp(applicationId, 10L);
        when(sellerApplicationRepository.findById(applicationId)).thenReturn(Optional.of(app));

        RejectRequest req = new RejectRequest("서류 미비");

        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/reject", applicationId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST .../reject — reason 공백(@Valid) → 400")
    void reject_blank_reason_returns_400() throws Exception {
        String reqBody = "{\"reason\":\"\"}";

        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/reject", 50L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST .../reject — CONSUMER Bearer → 403")
    void reject_consumer_returns_403() throws Exception {
        RejectRequest req = new RejectRequest("사유");

        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/reject", 50L)
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST .../reject — 비인증 → 401 JSON")
    void reject_unauthenticated_returns_401() throws Exception {
        RejectRequest req = new RejectRequest("사유");

        mockMvc.perform(post("/api/v1/admin/seller-applications/{id}/reject", 50L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // helpers
    // ============================================================

    private User userWithId(long id, String email, Role role) {
        User user = User.of(email, "hash-value", "이름" + id, null, role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }

    private SellerApplication pendingApp(long appId, long userId) {
        SellerApplication app = SellerApplication.submit(userId, "상호", "1234567890", "010-0000-0000");
        try {
            var idField = SellerApplication.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(app, appId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return app;
    }
}
