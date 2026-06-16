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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 전용 보안 체인(@Order(0)) 통합 테스트 (ADR-010 / Task 036).
 *
 * <p>검증 의도:
 * <ul>
 *   <li>관리 엔드포인트가 <b>미인증으로 접근 가능</b>(View 체인 formLogin 302에 더는 걸리지 않음).</li>
 *   <li>actuator 체인이 <b>다른 경로의 인가를 새로 열지 않음</b>(REST 보호 경로는 여전히 401) — 회귀 가드.</li>
 * </ul>
 *
 * <p>배선: 풀컨텍스트 보안 테스트 규약을 {@link SecurityConfigTest}에서 그대로 계승한다
 * (@MockSharedRepositories + @ActiveProfiles("test") + FakeRefreshTokenStore + 개별 @MockitoBean).
 *
 * <p>노출 설정: 테스트 classpath의 application.yml은 main의 management 섹션을 가리므로,
 * 본 테스트에서 명시적으로 노출·probe를 주입한다(아래 properties).
 *
 * <p>단언 선택: health 집계는 Redis 등 인프라 health indicator에 의존해 환경에 따라 503이 될 수 있어,
 * <b>인프라 비의존</b>인 liveness 그룹(LivenessState)과 prometheus 스크레이프로 "공개 도달성"을 단언한다.
 */
@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.endpoint.health.probes.enabled=true",
        "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
@MockSharedRepositories
class ActuatorSecurityTest {

    @org.springframework.beans.factory.annotation.Autowired
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
    @DisplayName("미인증 GET /actuator/prometheus → 200 (actuator 체인 permitAll, login 리다이렉트 없음)")
    void unauthenticated_prometheus_is_public() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("미인증 GET /actuator/health/liveness → 200 (health 엔드포인트 공개 도달 + liveness UP, 인프라 비의존)")
    void unauthenticated_health_liveness_is_public_and_up() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("회귀 가드: 미인증 GET /api/v1/orders → 401 (actuator 체인이 REST 보호 경로를 열지 않음)")
    void actuator_chain_does_not_open_protected_rest_path() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }
}
