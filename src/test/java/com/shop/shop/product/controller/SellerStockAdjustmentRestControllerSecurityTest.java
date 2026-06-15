package com.shop.shop.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.inventory.domain.VariantStock;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.inventory.repository.StockLedgerRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Product;
import com.shop.shop.product.domain.ProductVariant;
import com.shop.shop.product.dto.StockAdjustmentRequest;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.order.repository.CouponRepository;
import com.shop.shop.order.repository.UserCouponRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SellerStockAdjustmentRestController} 권한 매트릭스 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오 (api-authorization-rule):
 * <ul>
 *   <li>POST /stock-adjustments: SELLER 200 / ADMIN 200 / 타 SELLER product 404 / CONSUMER 403 / 비인증 401</li>
 *   <li>GET /ledger: 동일 매트릭스</li>
 *   <li>@Valid 검증: delta null → 400, memo 공란 → 400</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class SellerStockAdjustmentRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ── Repository Mocks (test 프로파일 DataSource 제외로 필요) ──
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
    private StockLedgerRepository stockLedgerRepository;
    @MockitoBean
    private OrderRepository orderRepository;
    @MockitoBean
    private ShipmentRepository shipmentRepository;
    @MockitoBean
    private PaymentRepository paymentRepository;
    @MockitoBean
    private CouponRepository couponRepository;
    @MockitoBean
    private UserCouponRepository userCouponRepository;
    @MockitoBean
    private ReviewRepository reviewRepository;
    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    private String adminToken;
    private String sellerToken;
    private String sellerToken2;
    private String consumerToken;

    private static final long SELLER_ID = 2L;
    private static final long SELLER2_ID = 5L;
    private static final long ADMIN_ID = 1L;
    private static final long PRODUCT_ID = 10L;
    private static final long VARIANT_ID = 50L;

    private static final String ADJUST_URL =
            "/api/v1/seller/products/" + PRODUCT_ID + "/variants/" + VARIANT_ID + "/stock-adjustments";
    private static final String LEDGER_URL =
            "/api/v1/seller/products/" + PRODUCT_ID + "/variants/" + VARIANT_ID + "/ledger";

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(ADMIN_ID, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(SELLER_ID, "seller@example.com", List.of("ROLE_SELLER"));
        sellerToken2 = jwtTokenProvider.createAccess(SELLER2_ID, "seller2@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 성공 stubbing (SELLER 200 케이스)
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        ProductVariant variant = sampleVariant(VARIANT_ID, product);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));

        // inventoryStockRepository.findByIdForUpdate → adjustStock 내부에서 사용
        VariantStock variantStockMock = mock(VariantStock.class);
        when(variantStockMock.getStock()).thenReturn(10);
        when(variantStockMock.isActive()).thenReturn(true);
        when(inventoryStockRepository.findByIdForUpdate(VARIANT_ID))
                .thenReturn(Optional.of(variantStockMock));

        // adjustStock → stockLedgerRepository.save 반환값으로 StockLedgerView 즉시 구성
        com.shop.shop.inventory.domain.StockLedgerEntry entry =
                com.shop.shop.inventory.domain.StockLedgerEntry.of(
                        VARIANT_ID, -3, com.shop.shop.inventory.spi.StockChangeReason.ADJUSTMENT,
                        10, 7, SELLER_ID, "손상 폐기", Instant.now());
        try {
            var idField = com.shop.shop.inventory.domain.StockLedgerEntry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entry, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(stockLedgerRepository.save(any())).thenReturn(entry);

        // getLedger 페이지 응답
        when(stockLedgerRepository.findByVariantIdOrderByOccurredAtDescIdDesc(
                anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
    }

    // ============================================================
    // POST /stock-adjustments
    // ============================================================

    @Test
    @DisplayName("POST stock-adjustments — SELLER 200")
    void adjustStock_seller_returns200() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-3, "손상 폐기");

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST stock-adjustments — ADMIN 200 (RoleHierarchy 함의)")
    void adjustStock_admin_returns200() throws Exception {
        // ADMIN은 actorIsAdmin=true → 소유권 바이패스
        Product product = sampleProduct(SELLER_ID, PRODUCT_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        StockAdjustmentRequest req = new StockAdjustmentRequest(-3, "손상 폐기");

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST stock-adjustments — 타 SELLER product → 404 (소유권 게이트 존재 은닉)")
    void adjustStock_otherSeller_returns404() throws Exception {
        // Product ownerId = SELLER_ID, variant는 해당 product에 올바르게 소속.
        // SELLER2(ownerId != actorId)가 요청 → ProductService.checkOwnership → ProductAccessDeniedException(404).
        // variant 미존재·소속불일치로 우연히 404가 나는 경로가 아님을 보장.
        // (setUp에서 product/variant가 이미 올바르게 배선되어 있어 소유권 게이트가 먼저 발동)
        StockAdjustmentRequest req = new StockAdjustmentRequest(-3, "손상 폐기");

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + sellerToken2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST stock-adjustments — CONSUMER → 403")
    void adjustStock_consumer_returns403() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-3, "손상 폐기");

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + consumerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST stock-adjustments — 비인증 → 401")
    void adjustStock_unauthenticated_returns401() throws Exception {
        StockAdjustmentRequest req = new StockAdjustmentRequest(-3, "손상 폐기");

        mockMvc.perform(post(ADJUST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST stock-adjustments — delta null → 400 (@Valid)")
    void adjustStock_nullDelta_returns400() throws Exception {
        String body = "{\"delta\":null,\"memo\":\"손상 폐기\"}";

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST stock-adjustments — memo 공란 → 400 (@Valid)")
    void adjustStock_blankMemo_returns400() throws Exception {
        String body = "{\"delta\":-3,\"memo\":\"\"}";

        mockMvc.perform(post(ADJUST_URL)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ============================================================
    // GET /ledger
    // ============================================================

    @Test
    @DisplayName("GET ledger — SELLER 200")
    void getLedger_seller_returns200() throws Exception {
        mockMvc.perform(get(LEDGER_URL)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET ledger — ADMIN 200 (RoleHierarchy 함의)")
    void getLedger_admin_returns200() throws Exception {
        mockMvc.perform(get(LEDGER_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET ledger — 타 SELLER product → 404 (소유권 게이트 존재 은닉)")
    void getLedger_otherSeller_returns404() throws Exception {
        // Product ownerId = SELLER_ID, variant는 해당 product에 올바르게 소속.
        // SELLER2가 요청 → ProductService.checkOwnership → ProductAccessDeniedException(404).
        // (setUp 기본 배선 그대로 사용 — 소유권 게이트가 먼저 발동함을 보장)
        mockMvc.perform(get(LEDGER_URL)
                        .header("Authorization", "Bearer " + sellerToken2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET ledger — CONSUMER → 403")
    void getLedger_consumer_returns403() throws Exception {
        mockMvc.perform(get(LEDGER_URL)
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET ledger — 비인증 → 401")
    void getLedger_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(LEDGER_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ============================================================
    // 헬퍼
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

    private ProductVariant sampleVariant(long variantId, Product product) {
        ProductVariant variant = ProductVariant.create(product, "SKU-TEST",
                new BigDecimal("10000"), 10, true, new HashSet<>());
        try {
            var idField = ProductVariant.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(variant, variantId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return variant;
    }
}
