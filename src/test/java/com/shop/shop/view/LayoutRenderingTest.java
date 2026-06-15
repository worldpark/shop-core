package com.shop.shop.view;

import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 레이아웃·프래그먼트 렌더링 통합 테스트.
 *
 * src/main/resources/templates 의 실제 템플릿을 classpath 정상 해석으로 렌더링한다.
 *
 * 마커 텍스트 계약 (view 산출물과 동일):
 *   - footer 마커: "2026 shop-core. All rights reserved."
 *   - header 마커: "shop-core"  (site-name 링크 텍스트)
 *   - nav 마커:    "홈"          (nav-link 텍스트)
 *
 * T1: GET /login  → 200, footer 마커 + name=username + _csrf 히든 포함
 * T2: GET /       → 200 (@WithMockUser), header·nav·footer 마커 + /css/app.css 링크 포함
 * T3: footer 마커가 login·home 양쪽 응답에 동일 등장
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class LayoutRenderingTest {

    @MockitoBean
    MemberRepository memberRepository;

    @MockitoBean
    SellerApplicationRepository sellerApplicationRepository;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    CategoryRepository categoryRepository;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductOptionRepository productOptionRepository;

    @MockitoBean
    OptionValueRepository optionValueRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    ProductImageRepository productImageRepository;

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartItemRepository cartItemRepository;

    @MockitoBean
    InventoryStockRepository inventoryStockRepository;

    @MockitoBean
    OrderRepository orderRepository;

    @MockitoBean
    ShipmentRepository shipmentRepository;

    @MockitoBean
    PaymentRepository paymentRepository;
    @MockitoBean
    private com.shop.shop.order.repository.CouponRepository couponRepository;

    @MockitoBean
    private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

    @MockitoBean
    private OrderItemQueryRepository orderItemQueryRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    /** footer 프래그먼트 식별 마커 — fragments/footer.html 의 실제 텍스트와 일치 */
    static final String FOOTER_MARKER = "2026 shop-core. All rights reserved.";

    /** header 프래그먼트 식별 마커 — site-name 링크 텍스트 */
    static final String HEADER_MARKER = "shop-core";

    /** nav 프래그먼트 식별 마커 — nav-link 텍스트 */
    static final String NAV_MARKER = "홈";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("(T1) GET /login → 200, footer 마커·name=username·_csrf 히든 포함")
    void login_page_includes_footer_and_form_fields() throws Exception {
        String body = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // footer 프래그먼트 마커
        assert body.contains(FOOTER_MARKER)
                : "login 페이지에 footer 마커가 없습니다. 실제 본문:\n" + body;

        // Spring Security UsernamePasswordAuthenticationFilter 필드명
        assert body.contains("name=\"username\"")
                : "login 페이지에 name=username 필드가 없습니다.";

        // th:action 자동 _csrf 주입 확인
        assert body.contains("_csrf")
                : "login 페이지에 _csrf 토큰이 없습니다.";
    }

    @Test
    @DisplayName("(T2) GET /(@WithMockUser) → 200, header·nav·footer 마커 + /css/app.css 링크 포함")
    @WithMockUser
    void home_page_includes_all_fragment_markers_and_css_link() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // header 프래그먼트 마커
        assert body.contains(HEADER_MARKER)
                : "home 페이지에 header 마커가 없습니다. 실제 본문:\n" + body;

        // nav 프래그먼트 마커
        assert body.contains(NAV_MARKER)
                : "home 페이지에 nav 마커가 없습니다.";

        // footer 프래그먼트 마커
        assert body.contains(FOOTER_MARKER)
                : "home 페이지에 footer 마커가 없습니다.";

        // 공통 CSS 링크
        assert body.contains("/css/app.css")
                : "home 페이지에 /css/app.css 링크가 없습니다.";
    }

    @Test
    @DisplayName("(T3) footer 마커가 login·home 양쪽 응답에 동일 등장")
    @WithMockUser
    void footer_marker_appears_in_both_login_and_home() throws Exception {
        String loginBody = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String homeBody = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assert loginBody.contains(FOOTER_MARKER)
                : "login 페이지에 footer 마커가 없습니다.";

        assert homeBody.contains(FOOTER_MARKER)
                : "home 페이지에 footer 마커가 없습니다.";
    }
}
