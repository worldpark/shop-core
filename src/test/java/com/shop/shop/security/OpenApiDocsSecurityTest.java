package com.shop.shop.security;

import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.repository.SellerApplicationRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.order.adapter.OrderItemQueryRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.order.repository.ShipmentRepository;
import com.shop.shop.payment.repository.PaymentRepository;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.product.repository.ReviewRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import com.shop.shop.support.MockSharedRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 문서 경로 노출 + 3체인 회귀 + prod 게이트 + 스펙 스모크 통합 테스트 (Task 062).
 *
 * <p>배선: ActuatorSecurityTest의 @MockSharedRepositories + @MockitoBean 목록을 그대로 계승.
 * 새 mock 전략 발명 금지(plan §10 명시).
 *
 * <p>노출 프로퍼티 명시 주입: 테스트 classpath의 application.yml이 main yml을 가리므로
 * springdoc 섹션이 부재한다. 노출 테스트에서는 ActuatorSecurityTest 선례와 동일하게
 * @SpringBootTest(properties=...)로 enabled=true를 명시 주입한다(암묵 의존 제거).
 *
 * <p>@MockSharedRepositories는 외부 클래스가 아닌 각 @Nested 클래스에만 붙인다.
 * 외부 클래스 + 중첩 클래스에 동시에 붙이면 Spring이 동일 타입 중복 BeanOverrideHandler
 * (StockLedgerRepository, OrderItemSalesRepository)를 감지하고 IllegalStateException을 던진다.
 */
class OpenApiDocsSecurityTest {

    // =========================================================
    // (1) 문서 경로 노출 (non-prod) + (2) 3체인 회귀 + (4) 스펙 스모크
    // =========================================================
    @Nested
    @SpringBootTest(properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            // actuator health/liveness 노출 — ActuatorSecurityTest와 동일한 명시 주입
            // (테스트 classpath yml이 main yml을 가리므로 management 섹션이 부재)
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "management.endpoint.health.probes.enabled=true"
    })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(FakeRefreshTokenStore.class)
    @MockSharedRepositories
    @DisplayName("non-prod: 문서 경로 노출 + 3체인 회귀 + 스펙 스모크")
    class NonProdExposureTests {

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
        private com.shop.shop.order.repository.CouponRepository couponRepository;

        @MockitoBean
        private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

        @MockitoBean
        private OrderItemQueryRepository orderItemQueryRepository;

        @MockitoBean
        private ReviewRepository reviewRepository;

        // ---- (1) 문서 경로 노출 ----

        @Test
        @DisplayName("(1a) 미인증 GET /v3/api-docs → 200 (openApiDocsChain permitAll, login 리다이렉트 없음)")
        void unauthenticated_api_docs_returns_200() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("(1b) 미인증 GET /swagger-ui.html → 200 또는 swagger-ui/** 리다이렉트 (login으로 가지 않음)")
        void unauthenticated_swagger_ui_not_redirected_to_login() throws Exception {
            var result = mockMvc.perform(get("/swagger-ui.html"))
                    .andReturn();

            int status = result.getResponse().getStatus();
            // 200 성공이거나 swagger-ui로 리다이렉트(springdoc이 /swagger-ui/index.html로 리다이렉트) — /login 아님
            if (status >= 300 && status < 400) {
                String location = result.getResponse().getHeader("Location");
                org.assertj.core.api.Assertions.assertThat(location)
                        .as("swagger-ui.html 리다이렉트는 /login으로 가면 안 됨")
                        .doesNotContainIgnoringCase("/login");
            } else {
                org.assertj.core.api.Assertions.assertThat(status)
                        .as("swagger-ui.html은 200 또는 3xx이어야 함")
                        .isBetween(200, 399);
            }
        }

        // ---- (2) 3체인 회귀 가드 ----

        @Test
        @DisplayName("(2a) 회귀: 미인증 GET /api/v1/orders → 401 (REST 체인 @Order(2) 무변경)")
        void unauthenticated_rest_api_returns_401() throws Exception {
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("(2b) 회귀: 미인증 GET / → 302 **/login (View 체인 @Order(3) 무변경)")
        void unauthenticated_view_path_redirects_to_login() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @DisplayName("(2c) 회귀: 미인증 GET /actuator/health/liveness → 200 (actuator 체인 @Order(0) 무변경)")
        void unauthenticated_actuator_health_liveness_is_public() throws Exception {
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk());
        }

        // ---- (4) 스펙 스모크 ----

        @Test
        @DisplayName("(4a) 스펙 스모크: /v3/api-docs 에 /api/v1/products 경로 존재")
        void spec_smoke_products_path_exists() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/products']").exists());
        }

        @Test
        @DisplayName("(4b) 스펙 스모크: /v3/api-docs 에 /api/v1/auth/login 경로 존재")
        void spec_smoke_auth_login_path_exists() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists());
        }

        @Test
        @DisplayName("(4c) 스펙 스모크: /v3/api-docs 에 /api/v1/orders 경로 존재")
        void spec_smoke_orders_path_exists() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/orders']").exists());
        }

        @Test
        @DisplayName("(4d) 스펙 스모크: components.securitySchemes.bearer-jwt 존재 (type=http, scheme=bearer)")
        void spec_smoke_bearer_jwt_security_scheme_exists() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt']").exists())
                    .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].type").value("http"))
                    .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"));
        }

        @Test
        @DisplayName("(4e) 스펙 스모크: View HTML 경로(/login, /cart)는 스펙에 부재 (GroupedOpenApi /api/v1/** 격리 확인)")
        void spec_smoke_view_html_paths_absent() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/login']").doesNotExist())
                    .andExpect(jsonPath("$.paths['/cart']").doesNotExist());
        }
    }

    // =========================================================
    // (3) prod 게이트 차단
    // =========================================================
    @Nested
    @SpringBootTest(properties = {
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false"
    })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(FakeRefreshTokenStore.class)
    @MockSharedRepositories
    @DisplayName("prod 게이트: enabled=false 시 404")
    class ProdGateTests {

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
        private com.shop.shop.order.repository.CouponRepository couponRepository;

        @MockitoBean
        private com.shop.shop.order.repository.UserCouponRepository userCouponRepository;

        @MockitoBean
        private OrderItemQueryRepository orderItemQueryRepository;

        @MockitoBean
        private ReviewRepository reviewRepository;

        @Test
        @DisplayName("(3a) prod 게이트: enabled=false → GET /v3/api-docs 404 (핸들러 미등록)")
        void prod_gate_api_docs_returns_404() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("(3b) prod 게이트: enabled=false → GET /swagger-ui.html 404 (핸들러 미등록)")
        void prod_gate_swagger_ui_returns_404() throws Exception {
            mockMvc.perform(get("/swagger-ui.html"))
                    .andExpect(status().isNotFound());
        }
    }
}
