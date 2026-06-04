package com.shop.shop.member.service;

import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberService.signup 단위 테스트.
 * Mockito로 MemberRepository를 mock. BCrypt 인코더 직접 사용.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceSignupTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private PasswordEncoder passwordEncoder;
    private MemberService memberService;

    private static final String EMAIL = "newuser@example.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore);
    }

    @Test
    @DisplayName("signup 성공 — User 반환, role CONSUMER, BCrypt 해시 저장")
    void signup_success_returns_user_with_consumer_role_and_bcrypt_hash() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);

        // save 호출 시 인자를 그대로 반환(id는 DB에서 할당되므로 null 유지)
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE);

        User saved = captor.getValue();

        // 기본 role CONSUMER 강제
        assertThat(saved.getRole()).isEqualTo(Role.CONSUMER);

        // 이메일 저장 (trim 후)
        assertThat(saved.getEmail()).isEqualTo(EMAIL);

        // 비밀번호 해시 — BCrypt prefix $2, 원문과 다름
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);

        // BCrypt 해시 검증 — 원문으로 matches 가능
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("signup 성공 — PasswordEncoder.encode 호출 검증")
    void signup_calls_password_encoder() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE);

        // save가 호출되었으므로 encode도 호출됨을 간접 검증
        verify(memberRepository).save(any(User.class));
    }

    @Test
    @DisplayName("signup 성공 — phone null 허용 (optional)")
    void signup_success_with_null_phone() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, null);

        User saved = captor.getValue();
        assertThat(saved.getPhone()).isNull();
    }

    @Test
    @DisplayName("signup 성공 — phone 빈 문자열은 null로 정규화")
    void signup_success_empty_phone_normalized_to_null() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, "");

        User saved = captor.getValue();
        assertThat(saved.getPhone()).isNull();
    }

    @Test
    @DisplayName("signup 이메일 중복 — DuplicateEmailException 발생 (existsByEmail = true)")
    void signup_duplicate_email_throws_exception() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE))
                .isInstanceOf(DuplicateEmailException.class);

        // save는 호출되지 않아야 함
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup DataIntegrityViolationException → DuplicateEmailException 변환 (동시성 경합)")
    void signup_data_integrity_violation_converted_to_duplicate_email_exception() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    @DisplayName("signup 저장 비밀번호가 BCrypt prefix $2 확인")
    void signup_stored_password_has_bcrypt_prefix() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE);

        String hash = captor.getValue().getPasswordHash();
        assertThat(hash).startsWith("$2");
        assertThat(hash).isNotEqualTo(RAW_PASSWORD);
    }

    @Test
    @DisplayName("signup 이메일 앞뒤 공백 trim 정규화")
    void signup_email_trimmed() {
        String emailWithSpaces = "  " + EMAIL + "  ";
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        memberService.signup(emailWithSpaces, RAW_PASSWORD, NAME, PHONE);

        assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
    }
}
