package com.shop.shop.product.controller;

import com.shop.shop.common.exception.CategoryNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.domain.Category;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminCategoryRestController DELETE 엔드포인트 보안 테스트.
 *
 * <p>검증 시나리오:
 * - DELETE /api/v1/admin/categories/{id}: ADMIN 204 / SELLER 403 / CONSUMER 403 / 비인증 401 / 존재X 404
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class AdminCategoryRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));

        // 기본 stub: 카테고리 목록 빈 리스트
        when(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    }

    // ============================================================
    // DELETE /api/v1/admin/categories/{categoryId}
    // ============================================================

    @Test
    @DisplayName("DELETE /api/v1/admin/categories/{id} — ADMIN → 204 No Content")
    void delete_admin_returns_204() throws Exception {
        Category existing = categoryWithId(1L, "전자", "electronics");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        doNothing().when(categoryRepository).delete(existing);

        mockMvc.perform(delete("/api/v1/admin/categories/1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/categories/{id} — SELLER → 403")
    void delete_seller_returns_403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/categories/1")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/categories/{id} — CONSUMER → 403")
    void delete_consumer_returns_403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/categories/1")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/categories/{id} — 비인증 → 401")
    void delete_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/categories/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/categories/{id} — 존재X → 404")
    void delete_not_found_returns_404() throws Exception {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/admin/categories/999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ============================================================
    // helpers
    // ============================================================

    private Category categoryWithId(long id, String name, String slug) {
        Category cat = Category.of(name, slug, null, 0);
        try {
            var idField = Category.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(cat, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cat;
    }
}
