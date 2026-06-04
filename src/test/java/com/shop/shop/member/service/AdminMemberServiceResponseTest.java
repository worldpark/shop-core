package com.shop.shop.member.service;

import com.shop.shop.common.dto.PageResponse;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.MemberSummaryResponse;
import com.shop.shop.member.dto.RoleChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminMemberServiceResponse 단위 테스트.
 * MemberService를 mock하여 위임·매핑·principal 추출을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminMemberServiceResponseTest {

    @Mock
    private MemberService memberService;

    @Mock
    private Authentication authentication;

    private AdminMemberServiceResponse adminMemberServiceResponse;

    @BeforeEach
    void setUp() {
        adminMemberServiceResponse = new AdminMemberServiceResponse(memberService);
    }

    // ============================================================
    // search → PageResponse 매핑
    // ============================================================

    @Test
    @DisplayName("search — memberService.searchMembers에 정확히 위임하고 PageResponse를 반환한다")
    void search_delegates_to_memberService_and_returns_PageResponse() {
        // given
        String keyword = "test";
        Role role = Role.SELLER;
        int page = 0;
        int size = 10;

        User user = userWithId(1L, "test@example.com", Role.SELLER);
        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage = new PageImpl<>(List.of(user), pageable, 1L);
        when(memberService.searchMembers(eq(keyword), eq(role), any(Pageable.class))).thenReturn(userPage);

        // when
        PageResponse<MemberSummaryResponse> result = adminMemberServiceResponse.search(keyword, role, page, size);

        // then
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(size); // PageRequest의 pageSize와 동일
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("search — MemberSummaryResponse에 민감정보(passwordHash 등) 필드가 없다")
    void search_response_does_not_contain_sensitive_fields() {
        // given: password 필드를 알아보기 쉬운 값으로 설정 후 DTO에 포함되지 않는지 확인
        User user = User.of("safe@example.com", "SECRET_HASH_VALUE", "안전이", null, Role.CONSUMER);
        setId(user, 2L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> userPage = new PageImpl<>(List.of(user), pageable, 1L);
        when(memberService.searchMembers(any(), any(), any(Pageable.class))).thenReturn(userPage);

        // when
        PageResponse<MemberSummaryResponse> result = adminMemberServiceResponse.search(null, null, 0, 20);

        // then
        MemberSummaryResponse dto = result.content().get(0);
        // MemberSummaryResponse record 필드: memberId, email, name, role, createdAt
        // passwordHash 필드가 record에 없음을 컴파일 타임에서 보장.
        // 런타임 검증: DTO의 모든 필드값에 password hash 값이 없음을 확인
        assertThat(dto.email()).isEqualTo("safe@example.com");
        assertThat(dto.name()).isEqualTo("안전이");
        assertThat(dto.role()).isEqualTo("CONSUMER");
        // memberId, email, name, role, createdAt 외에 password hash가 DTO 필드에 없음
        assertThat(dto.toString()).doesNotContain("SECRET_HASH_VALUE");
    }

    private void setId(User user, long id) {
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("search — 빈 결과도 PageResponse로 래핑된다")
    void search_empty_result_wrapped_in_PageResponse() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> emptyPage = new PageImpl<>(List.of(), pageable, 0L);
        when(memberService.searchMembers(any(), any(), any(Pageable.class))).thenReturn(emptyPage);

        // when
        PageResponse<MemberSummaryResponse> result = adminMemberServiceResponse.search(null, null, 0, 20);

        // then
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
    }

    // ============================================================
    // changeRole — principal(long) 추출 위임
    // ============================================================

    @Test
    @DisplayName("changeRole — authentication.getPrincipal()에서 adminUserId(long) 추출 후 memberService.changeRole에 위임")
    void changeRole_extracts_userId_from_principal_and_delegates() {
        // given
        long adminUserId = 42L;
        long targetId = 99L;
        RoleChangeRequest req = new RoleChangeRequest(Role.SELLER);
        when(authentication.getPrincipal()).thenReturn(adminUserId);

        // when
        adminMemberServiceResponse.changeRole(authentication, targetId, req);

        // then: adminUserId=42, targetId=99, role=SELLER로 memberService.changeRole 호출
        verify(memberService).changeRole(adminUserId, targetId, Role.SELLER);
    }

    @Test
    @DisplayName("changeRole — principal로부터 추출한 userId가 memberService.changeRole 첫 번째 인자로 전달된다")
    void changeRole_adminUserId_is_first_argument_to_memberService() {
        // given
        long adminUserId = 7L;
        long targetId = 13L;
        RoleChangeRequest req = new RoleChangeRequest(Role.CONSUMER);
        when(authentication.getPrincipal()).thenReturn(adminUserId);

        // when
        adminMemberServiceResponse.changeRole(authentication, targetId, req);

        // then: 인자 캡처로 정확한 위임 검증
        ArgumentCaptor<Long> adminIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> targetIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(memberService).changeRole(adminIdCaptor.capture(), targetIdCaptor.capture(), roleCaptor.capture());

        assertThat(adminIdCaptor.getValue()).isEqualTo(adminUserId);
        assertThat(targetIdCaptor.getValue()).isEqualTo(targetId);
        assertThat(roleCaptor.getValue()).isEqualTo(Role.CONSUMER);
    }

    // helpers

    private User userWithId(long id, String email, Role role) {
        User user = User.of(email, "some-hash", "이름" + id, null, role);
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
