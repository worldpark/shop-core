package com.shop.shop.member.service;

import com.shop.shop.common.exception.InvalidCredentialsException;
import com.shop.shop.common.exception.InvalidTokenException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * MemberService 단위 테스트.
 * Mockito로 MemberRepository를 mock. BCrypt 인코더 직접 사용.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;

    private MemberService memberService;

    private static final String EMAIL = "user@example.com";
    private static final String RAW_PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore, eventPublisher);
    }

    @Test
    @DisplayName("authenticate 성공 — 올바른 이메일/비밀번호로 User 반환")
    void authenticate_success() {
        User user = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "테스터", null, Role.CONSUMER);
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        User result = memberService.authenticate(EMAIL, RAW_PASSWORD);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("authenticate 실패 — 없는 이메일: InvalidCredentialsException (계정 열거 방지)")
    void authenticate_fails_on_unknown_email() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.authenticate(EMAIL, RAW_PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("authenticate 실패 — 비밀번호 불일치: InvalidCredentialsException (동일 메시지)")
    void authenticate_fails_on_wrong_password() {
        User user = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "테스터", null, Role.CONSUMER);
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> memberService.authenticate(EMAIL, "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("authenticate 실패 — 계정 열거 방지: 이메일 없음/비밀번호 불일치 메시지 동일")
    void authenticate_same_error_message_for_account_enumeration_prevention() {
        // 이메일 없음 케이스
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        String messageForUnknownEmail;
        try {
            memberService.authenticate(EMAIL, RAW_PASSWORD);
            throw new AssertionError("예외가 발생해야 합니다");
        } catch (InvalidCredentialsException e) {
            messageForUnknownEmail = e.getMessage();
        }

        // 비밀번호 불일치 케이스
        User user = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "테스터", null, Role.CONSUMER);
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        String messageForWrongPassword;
        try {
            memberService.authenticate(EMAIL, "wrong-password");
            throw new AssertionError("예외가 발생해야 합니다");
        } catch (InvalidCredentialsException e) {
            messageForWrongPassword = e.getMessage();
        }

        assertThat(messageForUnknownEmail).isEqualTo(messageForWrongPassword);
    }

    @Test
    @DisplayName("authenticate 실패 — WITHDRAWN 사용자: InvalidCredentialsException (동일 메시지로 계정 열거 방지)")
    void authenticate_fails_on_withdrawn_user() {
        // WITHDRAWN 상태 User 구성: of()는 ACTIVE로 생성하므로 withdraw()로 상태 전이
        User withdrawnUser = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "탈퇴자", null, Role.CONSUMER);
        withdrawnUser.withdraw();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(withdrawnUser));

        // WITHDRAWN + 비번 matches=true 여도 InvalidCredentialsException 발생
        assertThatThrownBy(() -> memberService.authenticate(EMAIL, RAW_PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("authenticate 실패 — WITHDRAWN 메시지가 이메일없음/비번불일치와 동일 (계정 열거 방지)")
    void authenticate_withdrawn_same_message_as_other_failures() {
        // 이메일 없음 케이스 메시지
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        String messageForUnknownEmail;
        try {
            memberService.authenticate(EMAIL, RAW_PASSWORD);
            throw new AssertionError("예외가 발생해야 합니다");
        } catch (InvalidCredentialsException e) {
            messageForUnknownEmail = e.getMessage();
        }

        // WITHDRAWN 케이스 메시지
        User withdrawnUser = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "탈퇴자", null, Role.CONSUMER);
        withdrawnUser.withdraw();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(withdrawnUser));
        String messageForWithdrawn;
        try {
            memberService.authenticate(EMAIL, RAW_PASSWORD);
            throw new AssertionError("예외가 발생해야 합니다");
        } catch (InvalidCredentialsException e) {
            messageForWithdrawn = e.getMessage();
        }

        assertThat(messageForWithdrawn).isEqualTo(messageForUnknownEmail);
    }

    @Test
    @DisplayName("authenticate 성공 — ACTIVE 사용자는 정상 통과 (WITHDRAWN 가드 대비)")
    void authenticate_active_user_passes_guard() {
        User activeUser = User.of(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "활성유저", null, Role.CONSUMER);
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser));

        User result = memberService.authenticate(EMAIL, RAW_PASSWORD);

        assertThat(result).isNotNull();
        assertThat(result.isActive()).isTrue();
    }

    @Test
    @DisplayName("getById 성공 — 존재하는 userId로 User 반환")
    void getById_success() {
        User user = User.of(EMAIL, "hash", "테스터", null, Role.CONSUMER);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = memberService.getById(1L);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getById 실패 — 없는 userId: InvalidTokenException")
    void getById_fails_on_unknown_id() {
        when(memberRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getById(999L))
                .isInstanceOf(InvalidTokenException.class);
    }
}
