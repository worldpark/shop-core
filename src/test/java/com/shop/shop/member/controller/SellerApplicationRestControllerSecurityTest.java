package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.SellerApplication;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.SellerApplicationRequest;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerApplicationRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>POST /api/v1/seller-applications: CONSUMER 201, SELLER/ADMIN 409(서비스 자격), 비인증 401, @Valid 400</li>
 *   <li>GET /api/v1/seller-applications/me: 본인 200, 없으면 404, 비인증 401</li>
 *   <li>응답에 passwordHash/token 미포함 단언</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerApplicationRestControllerSecurityTest {

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


    private String consumerToken;
    private String sellerToken;
    private String adminToken;

    private User consumerUser;
    private User sellerUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        consumerUser = userWithId(10L, "consumer@test.com", Role.CONSUMER);
        sellerUser = userWithId(20L, "seller@test.com", Role.SELLER);
        adminUser = userWithId(1L, "admin@test.com", Role.ADMIN);

        when(memberRepository.findById(10L)).thenReturn(Optional.of(consumerUser));
        when(memberRepository.findById(20L)).thenReturn(Optional.of(sellerUser));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        when(sellerApplicationRepository.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        consumerToken = jwtTokenProvider.createAccess(10L, "consumer@test.com", List.of("ROLE_CONSUMER"));
        sellerToken = jwtTokenProvider.createAccess(20L, "seller@test.com", List.of("ROLE_SELLER"));
        adminToken = jwtTokenProvider.createAccess(1L, "admin@test.com", List.of("ROLE_ADMIN"));
    }

    // ============================================================
    // POST /api/v1/seller-applications
    // ============================================================

    @Test
    @DisplayName("POST /api/v1/seller-applications — CONSUMER Bearer + 유효 요청 → 201 Created")
    void create_consumer_with_valid_request_returns_201() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        when(sellerApplicationRepository.existsByUserIdAndStatus(eq(10L), any())).thenReturn(false);
        SellerApplication saved = SellerApplication.submit(10L, "상호", "1234567890", "010-0000-0000");
        setId(saved, 100L);
        when(sellerApplicationRepository.save(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/seller-applications")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/v1/seller-applications — SELLER Bearer → 409(서비스 자격, 보안 403 아님)")
    void create_seller_returns_409_not_403() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        mockMvc.perform(post("/api/v1/seller-applications")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/seller-applications — ADMIN Bearer → 409(서비스 자격, 보안 403 아님)")
    void create_admin_returns_409_not_403() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        mockMvc.perform(post("/api/v1/seller-applications")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/seller-applications — 비인증 → 401 JSON")
    void create_unauthenticated_returns_401() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("상호", "1234567890", "010-0000-0000");

        mockMvc.perform(post("/api/v1/seller-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/v1/seller-applications — businessRegistrationNumber 11자리(패턴 오류) → 400")
    void create_invalid_business_number_returns_400() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("상호", "12345678901", "010-0000-0000");

        mockMvc.perform(post("/api/v1/seller-applications")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/seller-applications — businessName 공백 → 400")
    void create_blank_business_name_returns_400() throws Exception {
        SellerApplicationRequest req = new SellerApplicationRequest("", "1234567890", "010-0000-0000");

        mockMvc.perform(post("/api/v1/seller-applications")
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // GET /api/v1/seller-applications/me
    // ============================================================

    @Test
    @DisplayName("GET /api/v1/seller-applications/me — CONSUMER Bearer + 이력 있음 → 200")
    void me_consumer_with_history_returns_200() throws Exception {
        SellerApplication app = SellerApplication.submit(10L, "상호", "1234567890", "010-0000-0000");
        setId(app, 100L);
        when(sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(app));

        mockMvc.perform(get("/api/v1/seller-applications/me")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/seller-applications/me — SELLER Bearer(승격된 사용자) + 이력 있음 → 200")
    void me_seller_with_history_returns_200() throws Exception {
        // 승격된 SELLER도 본인 이력 조회 가능 (role 자격 제한 없음)
        SellerApplication app = SellerApplication.submit(20L, "상호", "1234567890", "010-0000-0000");
        setId(app, 101L);
        app.approve(1L);
        when(sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(20L))
                .thenReturn(Optional.of(app));

        mockMvc.perform(get("/api/v1/seller-applications/me")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("GET /api/v1/seller-applications/me — CONSUMER Bearer + 이력 없음 → 404")
    void me_consumer_without_history_returns_404() throws Exception {
        when(sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/seller-applications/me")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/seller-applications/me — 비인증 → 401 JSON")
    void me_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/seller-applications/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/seller-applications/me — 응답에 passwordHash 미포함")
    void me_response_does_not_contain_password_hash() throws Exception {
        SellerApplication app = SellerApplication.submit(10L, "상호", "1234567890", "010-0000-0000");
        setId(app, 100L);
        when(sellerApplicationRepository.findFirstByUserIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(app));

        String body = mockMvc.perform(get("/api/v1/seller-applications/me")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("password_hash");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("token");
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

    private void setId(SellerApplication app, long id) {
        try {
            var idField = SellerApplication.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(app, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
