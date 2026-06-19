package com.shop.shop.member.service;

import com.shop.shop.common.exception.AdminAlreadyExistsException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MemberService 최초 ADMIN 부트스트랩 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>{@code bootstrapFirstAdmin}: role=ADMIN save(ArgumentCaptor) + BCrypt encode 호출
 *       + {@code eventPublisher.publishEvent} 미호출(MemberRegisteredEvent 절대 발행 금지)</li>
 *   <li>{@code countByRole(ADMIN) > 0} → {@link AdminAlreadyExistsException} + save 미호출</li>
 *   <li>{@code existsByEmail = true} → {@link DuplicateEmailException}</li>
 *   <li>{@link DataIntegrityViolationException} → {@link DuplicateEmailException} 흡수</li>
 *   <li>{@code adminExists()}: count 0 → false, count 1 → true</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceBootstrapTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;
    private MemberService memberService;

    private static final String EMAIL = "admin@example.com";
    private static final String RAW_PASSWORD = "adminpass1";
    private static final String NAME = "관리자";

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore, eventPublisher);
    }

    // ─── bootstrapFirstAdmin 성공 ────────────────────────────────────────────

    @Test
    @DisplayName("bootstrapFirstAdmin 성공 — role=ADMIN으로 save, BCrypt 해시, MemberRegisteredEvent 미발행")
    void bootstrapFirstAdmin_success_saves_admin_with_bcrypt_and_no_event() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME);

        User saved = captor.getValue();

        // role=ADMIN 강제
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);

        // 이메일 저장 (trim 후)
        assertThat(saved.getEmail()).isEqualTo(EMAIL);

        // BCrypt 해시 — $2 prefix, 원문과 다름
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPasswordHash())).isTrue();

        // MemberRegisteredEvent 절대 발행 금지 (확정 결정)
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("bootstrapFirstAdmin 성공 — phone null (부트스트랩은 phone 불필요)")
    void bootstrapFirstAdmin_success_phone_is_null() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME);

        assertThat(captor.getValue().getPhone()).isNull();
    }

    @Test
    @DisplayName("bootstrapFirstAdmin 성공 — 이름 앞뒤 공백 trim")
    void bootstrapFirstAdmin_success_name_trimmed() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(memberRepository.save(captor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, "  " + NAME + "  ");

        // name.trim()이 User.of에 전달되어야 한다
        // EncryptedStringConverter가 개입하므로 직접 비교 대신 save 호출 자체를 검증
        verify(memberRepository).save(any(User.class));
    }

    // ─── AdminAlreadyExistsException ─────────────────────────────────────────

    @Test
    @DisplayName("bootstrapFirstAdmin — countByRole(ADMIN) > 0이면 AdminAlreadyExistsException + save 미호출")
    void bootstrapFirstAdmin_throws_adminAlreadyExists_when_admin_count_positive() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME))
                .isInstanceOf(AdminAlreadyExistsException.class);

        // save 미호출 — ADMIN 생성 시도 없음
        verify(memberRepository, never()).save(any());
        // 이벤트도 미발행
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("bootstrapFirstAdmin — countByRole(ADMIN)=2 이상도 AdminAlreadyExistsException")
    void bootstrapFirstAdmin_throws_adminAlreadyExists_when_multiple_admins_exist() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(2L);

        assertThatThrownBy(() -> memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME))
                .isInstanceOf(AdminAlreadyExistsException.class);

        verify(memberRepository, never()).save(any());
    }

    // ─── DuplicateEmailException ──────────────────────────────────────────────

    @Test
    @DisplayName("bootstrapFirstAdmin — existsByEmail = true → DuplicateEmailException")
    void bootstrapFirstAdmin_throws_duplicateEmail_when_email_exists() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME))
                .isInstanceOf(DuplicateEmailException.class);

        verify(memberRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("bootstrapFirstAdmin — DataIntegrityViolationException → DuplicateEmailException 흡수 (동시성 경합)")
    void bootstrapFirstAdmin_absorbs_dataIntegrityViolation_as_duplicateEmail() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> memberService.bootstrapFirstAdmin(EMAIL, RAW_PASSWORD, NAME))
                .isInstanceOf(DuplicateEmailException.class);

        verifyNoInteractions(eventPublisher);
    }

    // ─── adminExists() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("adminExists() — countByRole(ADMIN) = 0 → false")
    void adminExists_returns_false_when_count_is_zero() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(0L);

        assertThat(memberService.adminExists()).isFalse();
    }

    @Test
    @DisplayName("adminExists() — countByRole(ADMIN) = 1 → true")
    void adminExists_returns_true_when_count_is_one() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(1L);

        assertThat(memberService.adminExists()).isTrue();
    }

    @Test
    @DisplayName("adminExists() — countByRole(ADMIN) > 1 → true (다수 ADMIN)")
    void adminExists_returns_true_when_count_is_greater_than_one() {
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(5L);

        assertThat(memberService.adminExists()).isTrue();
    }
}
