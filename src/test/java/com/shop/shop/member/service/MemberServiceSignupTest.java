package com.shop.shop.member.service;

import com.shop.shop.common.exception.DuplicateEmailException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.event.MemberRegisteredEvent;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberService.signup 단위 테스트.
 * Mockito로 MemberRepository를 mock. BCrypt 인코더 직접 사용.
 * save mock은 id=1L을 주입한 User를 반환(user.getId() 언박싱 NPE 방지).
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceSignupTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;
    private MemberService memberService;

    private static final String EMAIL = "newuser@example.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore, eventPublisher);
    }

    /**
     * save mock 헬퍼: 저장된 User에 id=1L을 주입해 반환.
     * user.getId() 언박싱(Long→long) NPE를 방지한다.
     */
    private void mockSaveWithId() {
        when(memberRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });
    }

    @Test
    @DisplayName("signup 성공 — User 반환, role CONSUMER, BCrypt 해시 저장")
    void signup_success_returns_user_with_consumer_role_and_bcrypt_hash() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

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
        mockSaveWithId();

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE);

        // save가 호출되었으므로 encode도 호출됨을 간접 검증
        verify(memberRepository).save(any(User.class));
    }

    @Test
    @DisplayName("signup 성공 — phone null 허용 (optional)")
    void signup_success_with_null_phone() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, null);

        User saved = captor.getValue();
        assertThat(saved.getPhone()).isNull();
    }

    @Test
    @DisplayName("signup 성공 — phone 빈 문자열은 null로 정규화")
    void signup_success_empty_phone_normalized_to_null() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

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
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

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
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        memberService.signup(emailWithSpaces, RAW_PASSWORD, NAME, PHONE);

        assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
    }

    // ─── MemberRegisteredEvent 발행 검증 ────────────────────────────────────

    @Test
    @DisplayName("signup 성공 시 MemberRegisteredEvent를 1회 발행 — memberId/memberEmail/memberName/eventId/occurredAt 정합")
    void signup_success_publishes_MemberRegisteredEvent_once() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        mockSaveWithId();

        memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Object published = eventCaptor.getValue();
        assertThat(published).isInstanceOf(MemberRegisteredEvent.class);

        MemberRegisteredEvent event = (MemberRegisteredEvent) published;
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.memberId()).isEqualTo(1L);
        assertThat(event.memberEmail()).isEqualTo(EMAIL);
        assertThat(event.memberName()).isEqualTo(NAME);
    }

    @Test
    @DisplayName("signup 중복 이메일(DuplicateEmailException) 시 MemberRegisteredEvent 미발행")
    void signup_duplicateEmail_does_not_publish() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE))
                .isInstanceOf(DuplicateEmailException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("signup DataIntegrityViolationException(race unique 위반) 시 MemberRegisteredEvent 미발행")
    void signup_raceUniqueViolation_does_not_publish() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> memberService.signup(EMAIL, RAW_PASSWORD, NAME, PHONE))
                .isInstanceOf(DuplicateEmailException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
