package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.RoleChangeRequest;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.member.service.MemberUserDetailsService;
import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.product.repository.CategoryRepository;
import com.shop.shop.product.repository.ProductRepository;
import com.shop.shop.security.JwtTokenProvider;
import com.shop.shop.security.support.FakeRefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminMemberRestController + SecurityConfig REST 체인 MockMvc 통합 테스트.
 *
 * <p>검증 시나리오:
 * - GET /api/v1/admin/members: ADMIN 200 + PageResponse 구조, SELLER 403, CONSUMER 403, 비인증 401
 * - PATCH /api/v1/admin/members/{id}/role: 성공 200, 대상없음 404, 자기/마지막ADMIN 409, ADMIN승격 400,
 *   @NotNull 누락 400, SELLER 403, 비인증 401
 * - 응답에 password_hash/token 미포함 단언
 *
 * <p>FakeRefreshTokenStore: Redis 미기동 환경 비파괴 (@Import + @Primary).
 * MemberRepository: @MockBean stub (실 DB 미사용).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeRefreshTokenStore.class)
class AdminMemberRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private MemberUserDetailsService memberUserDetailsService;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private ProductRepository productRepository;

    private String adminToken;
    private String sellerToken;
    private String consumerToken;

    private User adminUser;
    private User sellerUser;
    private User consumerUser;

    @BeforeEach
    void setUp() {
        adminUser = userWithId(1L, "admin@example.com", Role.ADMIN);
        sellerUser = userWithId(2L, "seller@example.com", Role.SELLER);
        consumerUser = userWithId(3L, "consumer@example.com", Role.CONSUMER);

        // stub: findById
        when(memberRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(memberRepository.findById(2L)).thenReturn(Optional.of(sellerUser));
        when(memberRepository.findById(3L)).thenReturn(Optional.of(consumerUser));

        // stub: search → 빈 페이지 (기본)
        when(memberRepository.search(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        // JWT 토큰 생성 (userId = principal)
        adminToken = jwtTokenProvider.createAccess(1L, "admin@example.com", List.of("ROLE_ADMIN"));
        sellerToken = jwtTokenProvider.createAccess(2L, "seller@example.com", List.of("ROLE_SELLER"));
        consumerToken = jwtTokenProvider.createAccess(3L, "consumer@example.com", List.of("ROLE_CONSUMER"));
    }

    // ============================================================
    // GET /api/v1/admin/members
    // ============================================================

    @Test
    @DisplayName("GET /api/v1/admin/members — ADMIN Bearer → 200 + PageResponse 구조")
    void list_admin_returns_200_with_page_response_structure() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    @DisplayName("GET /api/v1/admin/members — SELLER Bearer → 403 ErrorResponse JSON")
    void list_seller_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/admin/members — CONSUMER Bearer → 403 ErrorResponse JSON")
    void list_consumer_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members")
                        .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/admin/members — 비인증(토큰 없음) → 401 ErrorResponse JSON (redirect 아님)")
    void list_unauthenticated_returns_401_json() throws Exception {
        mockMvc.perform(get("/api/v1/admin/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/admin/members — 응답에 password_hash 미포함")
    void list_response_does_not_contain_password_hash() throws Exception {
        User user = userWithId(10L, "test@example.com", Role.CONSUMER);
        when(memberRepository.search(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        String body = mockMvc.perform(get("/api/v1/admin/members")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 민감정보 미포함 단언
        assertFieldAbsent(body, "passwordHash");
        assertFieldAbsent(body, "password_hash");
        assertFieldAbsent(body, "token");
    }

    // ============================================================
    // PATCH /api/v1/admin/members/{id}/role
    // ============================================================

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — ADMIN 성공 → 200")
    void changeRole_admin_success_returns_200() throws Exception {
        long targetId = 3L;
        // stub: changeRole 호출 시 아무것도 안 함 (void)
        // countByRole stub (CONSUMER 강등이 아니므로 불필요, but stub anyway for safety)

        RoleChangeRequest req = new RoleChangeRequest(Role.SELLER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", targetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — 대상 없음 → 404 ErrorResponse JSON")
    void changeRole_target_not_found_returns_404() throws Exception {
        long nonExistId = 9999L;
        when(memberRepository.findById(nonExistId)).thenReturn(Optional.empty());

        RoleChangeRequest req = new RoleChangeRequest(Role.SELLER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", nonExistId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — 자기 ADMIN 강등 → 409 ErrorResponse JSON")
    void changeRole_self_admin_demotion_returns_409() throws Exception {
        // adminUserId=1(ADMIN)이 자기 자신(id=1)을 강등 시도
        long selfId = 1L;
        // adminUser는 ADMIN이므로 changeRole 불변식에서 selfDemotion 발생

        RoleChangeRequest req = new RoleChangeRequest(Role.CONSUMER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", selfId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — 마지막 ADMIN 강등 → 409 ErrorResponse JSON")
    void changeRole_last_admin_demotion_returns_409() throws Exception {
        // adminUserId=1이 다른 ADMIN(id=4)을 강등 시도 (countByRole=1)
        long anotherAdminId = 4L;
        User anotherAdmin = userWithId(4L, "admin2@example.com", Role.ADMIN);
        when(memberRepository.findById(4L)).thenReturn(Optional.of(anotherAdmin));
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(1L);

        RoleChangeRequest req = new RoleChangeRequest(Role.CONSUMER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", anotherAdminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — role=ADMIN 승격 시도 → 400 ErrorResponse JSON")
    void changeRole_admin_promotion_returns_400() throws Exception {
        long targetId = 3L;
        // consumerUser가 대상이지만 newRole=ADMIN 시도

        RoleChangeRequest req = new RoleChangeRequest(Role.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", targetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — @NotNull 누락(role null) → 400 ErrorResponse JSON")
    void changeRole_missing_role_returns_400() throws Exception {
        String reqBody = "{}"; // role 필드 누락

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", 3L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — SELLER Bearer → 403")
    void changeRole_seller_returns_403() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest(Role.CONSUMER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", 3L)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/members/{id}/role — 비인증 → 401 JSON")
    void changeRole_unauthenticated_returns_401() throws Exception {
        RoleChangeRequest req = new RoleChangeRequest(Role.CONSUMER);

        mockMvc.perform(patch("/api/v1/admin/members/{id}/role", 3L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // helpers

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

    private void assertFieldAbsent(String jsonBody, String fieldName) {
        // JSON 응답 본문에 특정 필드명이 없음을 단언
        org.assertj.core.api.Assertions.assertThat(jsonBody)
                .as("응답 본문에 민감정보 필드 '%s'가 포함되어선 안 됩니다", fieldName)
                .doesNotContain("\"" + fieldName + "\"");
    }
}
