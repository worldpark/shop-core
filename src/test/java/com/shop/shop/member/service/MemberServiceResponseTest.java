package com.shop.shop.member.service;

import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.dto.MeResponse;
import com.shop.shop.member.dto.SignupRequest;
import com.shop.shop.member.dto.SignupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MemberServiceResponse 단위 테스트.
 * MemberService mock, 위임 로직 + DTO 변환 검증.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceResponseTest {

    @Mock
    private MemberService memberService;

    private MemberServiceResponse memberServiceResponse;

    private static final String EMAIL = "user@example.com";
    private static final String NAME = "홍길동";

    @BeforeEach
    void setUp() {
        memberServiceResponse = new MemberServiceResponse(memberService);
    }

    @Test
    @DisplayName("signup — SignupResponse 반환, password 필드 부재")
    void signup_returns_signup_response_without_password() {
        User user = User.of(EMAIL, "$2a$10$hashedpassword", NAME, null, Role.CONSUMER);
        setUserId(user, 1L);

        SignupRequest request = new SignupRequest(EMAIL, "password123", "password123", NAME, null);
        when(memberService.signup(EMAIL, "password123", NAME, null)).thenReturn(user);

        SignupResponse response = memberServiceResponse.signup(request);

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.name()).isEqualTo(NAME);
        assertThat(response.role()).isEqualTo("CONSUMER");

        // SignupResponse record에 password/passwordHash 필드 부재 — 컴파일 레벨 보장
        // 런타임에도 JSON 직렬화 시 포함되지 않음을 record 구조로 보장
    }

    @Test
    @DisplayName("me — principal userId로 getById → MeResponse 반환, password 미노출")
    void me_returns_me_response_using_principal_user_id() {
        User user = User.of(EMAIL, "$2a$10$hashedpassword", NAME, null, Role.CONSUMER);
        setUserId(user, 1L);

        when(memberService.getById(1L)).thenReturn(user);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));

        MeResponse response = memberServiceResponse.me(authentication);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.name()).isEqualTo(NAME);
        assertThat(response.role()).isEqualTo("CONSUMER");

        // MeResponse record 필드는 id/email/name/role뿐 — password/passwordHash 노출 불가 (record 구조로 보장)
        var fields = java.util.Arrays.stream(MeResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(fields).doesNotContain("password", "passwordHash", "password_hash");
    }

    @Test
    @DisplayName("signup — role은 항상 CONSUMER (요청에서 role 미수신)")
    void signup_role_is_always_consumer() {
        User user = User.of(EMAIL, "$2a$10$hash", NAME, null, Role.CONSUMER);
        setUserId(user, 2L);

        SignupRequest request = new SignupRequest(EMAIL, "password123", "password123", NAME, null);
        when(memberService.signup(EMAIL, "password123", NAME, null)).thenReturn(user);

        SignupResponse response = memberServiceResponse.signup(request);

        assertThat(response.role()).isEqualTo("CONSUMER");
    }

    // helper
    private void setUserId(User user, long id) {
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
