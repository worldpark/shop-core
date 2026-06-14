package com.shop.shop.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.member.dto.PasswordChangeRequest;
import com.shop.shop.member.dto.ProfileUpdateRequest;
import com.shop.shop.member.service.AccountServiceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemberAccountRestController Security/REST 테스트 (MockMvc).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>PATCH /me/password — 인증 본인 성공 204, 비인증 401</li>
 *   <li>PATCH /me — 인증 본인 성공 204, 비인증 401</li>
 *   <li>DELETE /me — 인증 본인 성공 204, 비인증 401</li>
 *   <li>현재 비밀번호 불일치 — 400 BusinessException → ErrorResponse</li>
 *   <li>셀프 경로만 존재 (타 회원 id 받는 경로 부재)</li>
 * </ul>
 */
@WebMvcTest(controllers = MemberAccountRestController.class)
class MemberAccountRestControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountServiceResponse accountServiceResponse;

    private static final long USER_ID = 1L;

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                USER_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }

    // ============================================================
    // PATCH /api/v1/members/me/password
    // ============================================================

    @Test
    @DisplayName("PATCH /me/password — 인증 사용자 성공 204")
    void patch_password_authenticated_returns_204() throws Exception {
        doNothing().when(accountServiceResponse).changePassword(any(), any());

        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "newPassword1");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /me/password — 비인증 요청 401")
    void patch_password_unauthenticated_returns_401() throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "newPassword1");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /me/password — 현재 비밀번호 불일치 시 400 BusinessException")
    void patch_password_wrong_current_password_returns_400() throws Exception {
        doThrow(new BusinessException("현재 비밀번호가 일치하지 않습니다."))
                .when(accountServiceResponse).changePassword(any(), any());

        PasswordChangeRequest request = new PasswordChangeRequest(
                "wrongCurrent", "newPassword1", "newPassword1");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /me/password — @Valid 실패 시 400 (빈 바디)")
    void patch_password_invalid_request_returns_400() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/password")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // PATCH /api/v1/members/me
    // ============================================================

    @Test
    @DisplayName("PATCH /me — 인증 사용자 성공 204")
    void patch_profile_authenticated_returns_204() throws Exception {
        doNothing().when(accountServiceResponse).updateProfile(any(), any());

        ProfileUpdateRequest request = new ProfileUpdateRequest("새 이름", "010-9999-8888");

        mockMvc.perform(patch("/api/v1/members/me")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /me — 비인증 요청 401")
    void patch_profile_unauthenticated_returns_401() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest("새 이름", null);

        mockMvc.perform(patch("/api/v1/members/me")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /me — name 빈 문자열 → 400 @NotBlank 위반")
    void patch_profile_blank_name_returns_400() throws Exception {
        ProfileUpdateRequest request = new ProfileUpdateRequest("", null);

        mockMvc.perform(patch("/api/v1/members/me")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // DELETE /api/v1/members/me
    // ============================================================

    @Test
    @DisplayName("DELETE /me — 인증 사용자 성공 204")
    void delete_me_authenticated_returns_204() throws Exception {
        doNothing().when(accountServiceResponse).withdraw(any());

        mockMvc.perform(delete("/api/v1/members/me")
                        .with(authentication(auth()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /me — 비인증 요청 401")
    void delete_me_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================
    // 셀프 경로 구조 확인 — 타 회원 id 받는 경로 부재
    // ============================================================

    @Test
    @DisplayName("셀프 경로만 존재 — /me/password는 인증 사용자 접근 가능 (타 id 경로 없음)")
    void only_self_paths_exist_no_other_user_id_in_path() throws Exception {
        // /api/v1/members/{someId}/password 같은 경로는 이 컨트롤러에 없음
        // 해당 경로로 요청하면 404 또는 다른 컨트롤러로 라우팅됨
        // 여기서는 컨트롤러가 /me 고정 경로만 처리함을 확인
        doNothing().when(accountServiceResponse).changePassword(any(), any());

        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "newPassword1");

        // /me/password 경로는 정상 처리
        mockMvc.perform(patch("/api/v1/members/me/password")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // /999/password 같은 경로는 이 컨트롤러에 매핑 없음 → 404
        mockMvc.perform(patch("/api/v1/members/999/password")
                        .with(authentication(auth()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
