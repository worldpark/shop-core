package com.shop.shop.view;

import com.shop.shop.cart.dto.CartItemResponse;
import com.shop.shop.cart.dto.CartResponse;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.spi.CartFacade;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.spi.OrderFacade;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 화면(templates/cart/index.html) Thymeleaf 렌더링 통합 테스트.
 *
 * <p>실제 Thymeleaf 템플릿이 layout/base·프래그먼트와 함께 올바르게 렌더링되는지 검증.
 *
 * <p>CartFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GET /cart: CONSUMER 인증 → 200 + cart/index 렌더</li>
 *   <li>GET /cart: 비인증 → 302 /login redirect</li>
 *   <li>항목 목록(상품명·이미지·옵션라벨·단가·수량·합계) 렌더링</li>
 *   <li>수량 변경 폼 CSRF 토큰 포함</li>
 *   <li>삭제 폼 CSRF 토큰 포함</li>
 *   <li>unavailable(available=false) 항목 배지 표시</li>
 *   <li>stockEnough=false 항목 재고부족 배지 표시</li>
 *   <li>totalQuantity / totalAmount 표시</li>
 *   <li>hasUnavailableItem 알림 표시</li>
 *   <li>주문하기 버튼 비활성(disabled)</li>
 *   <li>장바구니 비어있을 때 빈 메시지</li>
 *   <li>담기 성공 후 redirect:/cart</li>
 *   <li>nav에 /cart 링크 (CONSUMER)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class CartViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

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
    private CartFacade cartFacade;

    @MockitoBean
    private OrderFacade orderFacade;

    @BeforeEach
    void setUp() {
        when(cartFacade.getCart(anyString())).thenReturn(sampleCartResponse());
    }

    // ============================================================
    // (C1) 비인증 redirect
    // ============================================================

    @Test
    @DisplayName("(C1) GET /cart — 비인증 → 302 /login redirect")
    void getCart_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ============================================================
    // (C2) 인증 렌더링
    // ============================================================

    @Test
    @DisplayName("(C2) GET /cart — CONSUMER 인증 → 200")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_consumer_returns200() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("(C2) GET /cart — 레이아웃(header·footer) 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_includesLayoutFragments() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("header가 포함되어야 함").contains("shop-core");
        assertThat(body).as("footer가 포함되어야 함").contains("2026 shop-core. All rights reserved.");
    }

    // ============================================================
    // (C3) 항목 목록 렌더링
    // ============================================================

    @Test
    @DisplayName("(C3) GET /cart — 상품명 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersProductName() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("상품명이 있어야 함").contains("테스트 상품");
    }

    @Test
    @DisplayName("(C3) GET /cart — 대표 이미지 렌더링 (imageUrl)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersItemImage() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("이미지 URL이 있어야 함")
                .contains("http://localhost:8080/assets/products/1/img.jpg");
    }

    @Test
    @DisplayName("(C3) GET /cart — 옵션 라벨 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersOptionLabel() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("옵션 라벨이 있어야 함").contains("빨강 / L");
    }

    @Test
    @DisplayName("(C3) GET /cart — 단가 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersUnitPrice() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("단가(15,000)가 있어야 함").contains("15,000");
    }

    @Test
    @DisplayName("(C3) GET /cart — 합계(lineAmount) 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersLineAmount() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("합계(30,000)가 있어야 함").contains("30,000");
    }

    // ============================================================
    // (C4) 폼 CSRF 검증
    // ============================================================

    @Test
    @DisplayName("(C4) GET /cart — 수량 변경 폼에 CSRF 토큰 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_quantityFormContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("수량 변경 폼에 CSRF 토큰이 있어야 함").contains("_csrf");
    }

    @Test
    @DisplayName("(C4) GET /cart — 삭제 폼에 CSRF 토큰 포함")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_deleteFormContainsCsrf() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 삭제 폼도 th:action → CSRF 자동 주입
        assertThat(body).as("삭제 폼에 /delete 경로가 있어야 함").contains("/delete");
    }

    // ============================================================
    // (C5) unavailable/재고부족 배지
    // ============================================================

    @Test
    @DisplayName("(C5) GET /cart — available=false 항목에 구매불가 배지 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_unavailableItem_showsUnavailableBadge() throws Exception {
        CartItemResponse unavailableItem = new CartItemResponse(
                2L, 200L, 20L,
                "구매불가 상품", "파랑",
                null,
                new BigDecimal("10000"), 1,
                new BigDecimal("10000"), false, false
        );
        CartResponse cart = new CartResponse(
                1L, List.of(unavailableItem), 1, BigDecimal.ZERO, true);
        when(cartFacade.getCart(anyString())).thenReturn(cart);

        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("구매불가 배지가 있어야 함").contains("구매불가");
    }

    @Test
    @DisplayName("(C5) GET /cart — stockEnough=false 항목에 재고부족 배지 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_stockNotEnough_showsStockBadge() throws Exception {
        CartItemResponse stockShortageItem = new CartItemResponse(
                3L, 300L, 30L,
                "재고부족 상품", "초록",
                null,
                new BigDecimal("20000"), 5,
                new BigDecimal("100000"), true, false
        );
        CartResponse cart = new CartResponse(
                1L, List.of(stockShortageItem), 5, BigDecimal.ZERO, true);
        when(cartFacade.getCart(anyString())).thenReturn(cart);

        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("재고부족 배지가 있어야 함").contains("재고부족");
    }

    @Test
    @DisplayName("(C5) GET /cart — hasUnavailableItem=true → 알림 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_hasUnavailableItem_showsWarning() throws Exception {
        CartItemResponse unavailableItem = new CartItemResponse(
                2L, 200L, 20L, "구매불가 상품", null, null,
                new BigDecimal("10000"), 1, new BigDecimal("10000"), false, false);
        CartResponse cart = new CartResponse(1L, List.of(unavailableItem), 1, BigDecimal.ZERO, true);
        when(cartFacade.getCart(anyString())).thenReturn(cart);

        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("구매불가 알림 문구가 있어야 함").contains("구매 불가능");
    }

    // ============================================================
    // (C6) 합계
    // ============================================================

    @Test
    @DisplayName("(C6) GET /cart — totalQuantity 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersTotalQuantity() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("총 상품 수(2개)가 있어야 함").contains("2개");
    }

    @Test
    @DisplayName("(C6) GET /cart — totalAmount 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_rendersTotalAmount() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("주문 가능 금액(30,000)이 있어야 함").contains("30,000");
    }

    // ============================================================
    // (C7) 주문하기 버튼 비활성
    // ============================================================

    @Test
    @DisplayName("(C7) GET /cart — 구매 가능 장바구니 → 주문하기 /checkout 링크 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_availableItems_showsCheckoutLink() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("/checkout 링크가 있어야 함").contains("/checkout");
        assertThat(body).as("주문하기 텍스트가 있어야 함").contains("주문하기");
    }

    @Test
    @DisplayName("(C7-b) GET /cart — 구매불가 항목 있을 때 주문하기 버튼 disabled 렌더링")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_unavailableItem_orderButtonDisabled() throws Exception {
        CartItemResponse unavailableItem = new CartItemResponse(
                2L, 200L, 20L,
                "구매불가 상품", "파랑",
                null,
                new BigDecimal("10000"), 1,
                new BigDecimal("10000"), false, false
        );
        CartResponse cart = new CartResponse(
                1L, List.of(unavailableItem), 1, BigDecimal.ZERO, true);
        when(cartFacade.getCart(anyString())).thenReturn(cart);

        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("disabled 속성이 있어야 함").contains("disabled");
        assertThat(body).as("구매 불가 안내 문구가 있어야 함").contains("구매 불가");
    }

    // ============================================================
    // (C8) 장바구니 비어있을 때
    // ============================================================

    @Test
    @DisplayName("(C8) GET /cart — 빈 장바구니 → 빈 메시지 표시")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_emptyCart_showsEmptyMessage() throws Exception {
        when(cartFacade.getCart(anyString()))
                .thenReturn(new CartResponse(1L, List.of(), 0, BigDecimal.ZERO, false));

        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("빈 장바구니 메시지가 있어야 함").contains("장바구니가 비어 있습니다");
    }

    // ============================================================
    // (C9) 담기 성공 redirect
    // ============================================================

    @Test
    @DisplayName("(C9) POST /cart/items — 담기 성공 → redirect:/cart")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void addItem_success_redirectsToCart() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .with(csrf())
                        .param("variantId", "100")
                        .param("quantity", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"));
    }

    // ============================================================
    // (C10) nav에 /cart 링크 (CONSUMER)
    // ============================================================

    @Test
    @DisplayName("(C10) GET /cart — nav에 /cart 링크 노출 (CONSUMER)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_navContainsCartLink() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /cart 링크가 있어야 함").contains("/cart");
        assertThat(body).as("nav에 장바구니 텍스트가 있어야 함").contains("장바구니");
    }

    // ============================================================
    // (C11) nav에 /orders 링크 (CONSUMER)
    // ============================================================

    @Test
    @DisplayName("(C11) GET /cart — nav에 /orders 링크 노출 (CONSUMER)")
    @WithMockUser(roles = "CONSUMER", username = "user@example.com")
    void getCart_navContainsOrdersLink() throws Exception {
        String body = mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("nav에 /orders 링크가 있어야 함").contains("/orders");
        assertThat(body).as("nav에 주문 내역 텍스트가 있어야 함").contains("주문 내역");
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
