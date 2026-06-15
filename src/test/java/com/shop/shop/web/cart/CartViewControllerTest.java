package com.shop.shop.web.cart;

import com.shop.shop.cart.dto.CartItemResponse;
import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.spi.CartFacade;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.common.exception.CartItemNotFoundException;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * CartViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>CartFacade(@MockitoBean)를 통해 facade 배선·모델 키·redirect·flashError를 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>비인증 → /login redirect (302)</li>
 *   <li>CONSUMER → GET /cart 200, view name cart/index, 모델 cart 존재</li>
 *   <li>cartFacade.getCart(email) 호출 검증 (email 전달)</li>
 *   <li>POST /cart/items 성공 → redirect:/cart</li>
 *   <li>POST /cart/items 검증 실패 (variantId null) → flashError + redirect:/cart</li>
 *   <li>POST /cart/items/{id} 수량변경 성공 → redirect:/cart</li>
 *   <li>POST /cart/items/{id} 검증 실패 (quantity=0) → flashError + redirect:/cart</li>
 *   <li>POST /cart/items/{id}/delete 성공 → redirect:/cart</li>
 *   <li>타인/미존재 cartItem (404) → ViewExceptionHandler error 뷰</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class CartViewControllerTest {

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

    /**
     * CartFacade @MockitoBean — cart.spi facade 격리.
     * cart 도메인 내부 Service·Repository와 무관하게 컨트롤러만 테스트한다.
     */
    @MockitoBean
    private CartFacade cartFacade;

    /**
     * OrderFacade @MockitoBean — order.spi facade 격리.
     * OrderViewController가 OrderFacade를 의존하므로 테스트 컨텍스트에서 mock 등록 필요.
     */
    @MockitoBean
    private OrderFacade orderFacade;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    private static final String USER_EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        CartResponse emptyCart = new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false);
        when(cartFacade.getCart(anyString())).thenReturn(emptyCart);
    }

    // ============================================================
    // GET /cart — 비인증/인증 접근
    // ============================================================

    @Test
    @DisplayName("GET /cart — 비인증 → /login redirect (302)")
    void getCart_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /cart — CONSUMER → 200")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void getCart_consumer_returns200() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /cart — CONSUMER → view name cart/index")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void getCart_consumer_returnsCartIndexView() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/index"));
    }

    @Test
    @DisplayName("GET /cart — model에 cart 키 존재")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void getCart_modelContainsCart() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("cart"));
    }

    @Test
    @DisplayName("GET /cart — cartFacade.getCart(email) 호출 검증 (email 전달)")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void getCart_delegatesToFacadeWithEmail() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk());

        verify(cartFacade).getCart(USER_EMAIL);
    }

    // ============================================================
    // POST /cart/items — 담기
    // ============================================================

    @Test
    @DisplayName("POST /cart/items — 성공 → redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void addItem_success_redirectsToCart() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .with(csrf())
                        .param("variantId", "100")
                        .param("quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"));

        verify(cartFacade).addItem(USER_EMAIL, 100L, 2);
    }

    @Test
    @DisplayName("POST /cart/items — variantId null → flashError + redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void addItem_variantIdNull_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .with(csrf())
                        .param("quantity", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /cart/items — quantity=0 → flashError + redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void addItem_quantityZero_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .with(csrf())
                        .param("variantId", "100")
                        .param("quantity", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /cart/items — email이 facade.addItem에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void addItem_emailPassedToFacade() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .with(csrf())
                        .param("variantId", "200")
                        .param("quantity", "1"))
                .andExpect(status().is3xxRedirection());

        verify(cartFacade).addItem(eq(USER_EMAIL), eq(200L), eq(1));
    }

    // ============================================================
    // POST /cart/items/{cartItemId} — 수량 변경
    // ============================================================

    @Test
    @DisplayName("POST /cart/items/{id} — 성공 → redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void updateQuantity_success_redirectsToCart() throws Exception {
        long cartItemId = 10L;

        mockMvc.perform(post("/cart/items/" + cartItemId)
                        .with(csrf())
                        .param("quantity", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"));

        verify(cartFacade).updateQuantity(USER_EMAIL, cartItemId, 3);
    }

    @Test
    @DisplayName("POST /cart/items/{id} — quantity=0 → flashError + redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void updateQuantity_quantityZero_flashErrorAndRedirect() throws Exception {
        mockMvc.perform(post("/cart/items/10")
                        .with(csrf())
                        .param("quantity", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /cart/items/{id} — email이 facade.updateQuantity에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void updateQuantity_emailPassedToFacade() throws Exception {
        mockMvc.perform(post("/cart/items/10")
                        .with(csrf())
                        .param("quantity", "2"))
                .andExpect(status().is3xxRedirection());

        verify(cartFacade).updateQuantity(eq(USER_EMAIL), eq(10L), eq(2));
    }

    @Test
    @DisplayName("POST /cart/items/{id} — 타인/미존재 cartItem (404) → error 뷰 렌더링")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void updateQuantity_notFoundCartItem_returns404() throws Exception {
        doThrow(new CartItemNotFoundException())
                .when(cartFacade).updateQuantity(anyString(), eq(99L), anyInt());

        mockMvc.perform(post("/cart/items/99")
                        .with(csrf())
                        .param("quantity", "1"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // POST /cart/items/{cartItemId}/delete — 삭제
    // ============================================================

    @Test
    @DisplayName("POST /cart/items/{id}/delete — 성공 → redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void removeItem_success_redirectsToCart() throws Exception {
        long cartItemId = 20L;

        mockMvc.perform(post("/cart/items/" + cartItemId + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"));

        verify(cartFacade).removeItem(USER_EMAIL, cartItemId);
    }

    @Test
    @DisplayName("POST /cart/items/{id}/delete — email이 facade.removeItem에 전달됨")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void removeItem_emailPassedToFacade() throws Exception {
        mockMvc.perform(post("/cart/items/20/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(cartFacade).removeItem(eq(USER_EMAIL), eq(20L));
    }

    @Test
    @DisplayName("POST /cart/items/{id}/delete — 타인/미존재 (404) → error 뷰 렌더링")
    @WithMockUser(roles = "CONSUMER", username = USER_EMAIL)
    void removeItem_notFoundCartItem_returns404() throws Exception {
        doThrow(new CartItemNotFoundException())
                .when(cartFacade).removeItem(anyString(), eq(99L));

        mockMvc.perform(post("/cart/items/99/delete")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private CartResponse sampleCartResponse() {
        CartItemResponse item = new CartItemResponse(
                1L, 100L, 10L,
                "테스트 상품", "빨강 / L",
                "http://localhost:8080/assets/products/1/img.jpg",
                new BigDecimal("15000"), 2,
                new BigDecimal("30000"), true, true
        );
        return new CartResponse(1L, List.of(item), 2, new BigDecimal("30000"), false);
    }
}
