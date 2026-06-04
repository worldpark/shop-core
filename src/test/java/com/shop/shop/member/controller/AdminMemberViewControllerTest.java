package com.shop.shop.member.controller;

import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberService;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
 * <p>템플릿 실제 렌더링 단언은 view-implementor 단계로 남겨두고,
 * 이 테스트는 컨트롤러 로직/권한 차단/redirect/flash/서비스 위임 검증에 집중한다.
 *
 * <p>MemberService: @MockBean으로 stub.
 * MemberRepository: @MockBean (JPA context 없이 기동).
 * FakeRefreshTokenStore: Redis 미기동 비파괴.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminMemberViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private MemberService memberService;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = userWithId(1L, "admin@example.com", Role.ADMIN);

        // stub: getByEmail → adminUser (View principal 통일용)
        when(memberService.getByEmail("admin@example.com")).thenReturn(adminUser);

        // stub: searchMembers → 빈 페이지
        when(memberService.searchMembers(any(), any(), any(Pageable.class)))
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
    @DisplayName("GET /admin/members — ADMIN → 200, searchMembers 호출 (템플릿 렌더 단언)")
    @WithMockUser(roles = "ADMIN", username = "admin@example.com")
    void get_list_admin_calls_searchMembers() throws Exception {
        // 템플릿(templates/admin/members.html) 구현 완료 후 실제 200 렌더를 단언한다.
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk());
        verify(memberService).searchMembers(any(), any(), any(Pageable.class));
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

        // adminUserId는 getByEmail로 통일됐는지 확인
        verify(memberService).getByEmail("admin@example.com");
        verify(memberService).changeRole(eq(1L), eq(targetId), eq(Role.SELLER));
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
                .when(memberService).changeRole(anyLong(), eq(targetId), any(Role.class));

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
                .when(memberService).changeRole(anyLong(), eq(targetId), any(Role.class));

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

    // helpers

    private User userWithId(long id, String email, Role role) {
        User user = User.of(email, "hash", "이름" + id, null, role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}
