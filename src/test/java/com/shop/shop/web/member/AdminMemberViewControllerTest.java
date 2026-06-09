package com.shop.shop.web.member;

import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.member.spi.AdminMemberFacade;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.OptionValueRepository;
import com.shop.shop.product.repository.ProductOptionRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.product.repository.ProductImageRepository;
import com.shop.shop.product.repository.ProductVariantRepository;
import com.shop.shop.cart.repository.CartRepository;
import com.shop.shop.cart.repository.CartItemRepository;
import com.shop.shop.inventory.repository.InventoryStockRepository;
import com.shop.shop.order.repository.OrderRepository;
import com.shop.shop.payment.repository.PaymentRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminMemberViewController + SecurityConfig View 체인 MockMvc 통합 테스트.
 *
 * <p>web.member 패키지로 이동 (원래 member.controller 패키지).
 * AdminMemberFacade(@MockitoBean)를 통해 facade 배선 동작을 검증한다.
 *
 * <p>MemberRepository: @MockitoBean (JPA context 없이 기동).
 * FakeRefreshTokenStore: Redis 미기동 비파괴.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminMemberViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockitoBean
    private AdminMemberFacade adminMemberFacade;

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
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        // stub: searchMembers → 빈 페이지
        when(adminMemberFacade.searchMembers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    // ============================================================
    // 권한 차단 (View 체인 /admin/** hasRole ADMIN)
    // ============================================================

    @Test
    @DisplayName("GET /admin/members — 비인증 → /login redirect (302)")
    void get_list_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("GET /admin/members — SELLER → 403")
    @WithMockUser(roles = "SELLER")
    void get_list_seller_returns_403() throws Exception {
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/members — CONSUMER → 403")
    @WithMockUser(roles = "CONSUMER")
    void get_list_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // GET /admin/members (ADMIN 접근)
    // ============================================================

    @Test
    @DisplayName("GET /admin/members — ADMIN → 200, adminMemberFacade.searchMembers 호출")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_list_admin_calls_searchMembers() throws Exception {
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk());
        verify(adminMemberFacade).searchMembers(any(), any(), anyInt(), anyInt());
    }

    // ============================================================
    // POST /admin/members/{memberId}/role — 성공
    // ============================================================

    @Test
    @DisplayName("POST /admin/members/{id}/role — 성공 → redirect:/admin/members + flashSuccess")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_changeRole_success_redirects_with_flashSuccess() throws Exception {
        long targetId = 3L;

        mockMvc.perform(post("/admin/members/{id}/role", targetId)
                        .with(csrf())
                        .param("role", "SELLER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/members"))
                .andExpect(flash().attribute("flashSuccess", "권한이 변경되었습니다."));

        // facade.changeRole에 adminEmail, targetMemberId, role(String) 전달 검증
        verify(adminMemberFacade).changeRole(eq("admin@example.com"), eq(targetId), eq("SELLER"));
    }

    // ============================================================
    // POST /admin/members/{memberId}/role — 실패 (BusinessException)
    // ============================================================

    @Test
    @DisplayName("POST /admin/members/{id}/role — BusinessException 발생 → redirect:/admin/members + flashError")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_changeRole_failure_redirects_with_flashError() throws Exception {
        long targetId = 1L;
        doThrow(RoleChangeNotAllowedException.selfDemotion())
                .when(adminMemberFacade).changeRole(anyString(), eq(targetId), anyString());

        mockMvc.perform(post("/admin/members/{id}/role", targetId)
                        .with(csrf())
                        .param("role", "CONSUMER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/members"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @DisplayName("POST /admin/members/{id}/role — flashError 시 JSON 응답 아님 (View 체인 Constraint)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void post_changeRole_failure_is_not_json_response() throws Exception {
        long targetId = 1L;
        doThrow(RoleChangeNotAllowedException.selfDemotion())
                .when(adminMemberFacade).changeRole(anyString(), eq(targetId), anyString());

        String contentType = mockMvc.perform(post("/admin/members/{id}/role", targetId)
                        .with(csrf())
                        .param("role", "CONSUMER"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getContentType();

        // 3xx redirect이므로 body/content-type이 없거나 JSON이 아님
        if (contentType != null) {
            org.assertj.core.api.Assertions.assertThat(contentType)
                    .doesNotContain("application/json");
        }
    }

    @Test
    @DisplayName("POST /admin/members/{id}/role — 비인증 → /login redirect")
    void post_changeRole_unauthenticated_redirects_to_login() throws Exception {
        mockMvc.perform(post("/admin/members/{id}/role", 3L)
                        .with(csrf())
                        .param("role", "SELLER"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("POST /admin/members/{id}/role — SELLER → 403")
    @WithMockUser(roles = "SELLER")
    void post_changeRole_seller_returns_403() throws Exception {
        mockMvc.perform(post("/admin/members/{id}/role", 3L)
                        .with(csrf())
                        .param("role", "CONSUMER"))
                .andExpect(status().isForbidden());
    }
}
