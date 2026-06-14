package com.shop.shop.member.service;

import com.shop.shop.common.exception.BusinessException;
import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.member.domain.MemberStatus;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AccountService 단위 테스트 (Mockito).
 *
 * <p>트랜잭션 동기화 비활성 컨텍스트(테스트 환경)에서 deleteRefresh 직접 호출 분기 검증.
 * TransactionSynchronizationManager.isSynchronizationActive() = false → else 분기 실행.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private AccountService accountService;

    private static final long USER_ID = 1L;
    private static final String EMAIL = "user@example.com";
    private static final String CURRENT_HASH = "$2a$10$current_hash";
    private static final String CURRENT_PW = "currentPassword1";
    private static final String NEW_PW = "newPassword1";
    private static final String NEW_HASH = "$2a$10$new_hash";

    @BeforeEach
    void setUp() {
        accountService = new AccountService(memberRepository, passwordEncoder, refreshTokenStore);
    }

    // ============================================================
    // changePassword
    // ============================================================

    @Test
    @DisplayName("changePassword — 현재 비밀번호 불일치 시 BusinessException(400), changePassword 미호출")
    void changePassword_throws_when_current_password_mismatches() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PW, CURRENT_HASH)).thenReturn(false);

        assertThatThrownBy(() -> accountService.changePassword(USER_ID, CURRENT_PW, NEW_PW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("현재 비밀번호가 일치하지 않습니다");

        // user.changePassword 미호출 확인 — 해시는 변경되지 않아야 함
        assertThat(user.getPasswordHash()).isEqualTo(CURRENT_HASH);
        // refresh 무효화 미호출 확인
        verify(refreshTokenStore, never()).deleteRefresh(USER_ID);
    }

    @Test
    @DisplayName("changePassword — 현재 비밀번호 불일치 시 encode 미호출")
    void changePassword_does_not_encode_when_current_password_mismatches() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PW, CURRENT_HASH)).thenReturn(false);

        assertThatThrownBy(() -> accountService.changePassword(USER_ID, CURRENT_PW, NEW_PW))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("changePassword 성공 — encode(newPassword) 호출, user.changePassword(newHash), deleteRefresh 직접호출(동기화 비활성)")
    void changePassword_success_encodes_and_updates_hash_and_invalidates_refresh() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PW, CURRENT_HASH)).thenReturn(true);
        when(passwordEncoder.encode(NEW_PW)).thenReturn(NEW_HASH);

        accountService.changePassword(USER_ID, CURRENT_PW, NEW_PW);

        // encode(newPassword) 호출 확인
        verify(passwordEncoder).encode(NEW_PW);
        // user.changePassword(newHash) 적용 확인
        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH);
        // 동기화 비활성 컨텍스트 → deleteRefresh 직접 호출
        verify(refreshTokenStore).deleteRefresh(USER_ID);
    }

    @Test
    @DisplayName("changePassword — userId가 존재하지 않으면 MemberNotFoundException")
    void changePassword_throws_member_not_found_when_user_absent() {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.changePassword(USER_ID, CURRENT_PW, NEW_PW))
                .isInstanceOf(MemberNotFoundException.class);
    }

    // ============================================================
    // updateProfile
    // ============================================================

    @Test
    @DisplayName("updateProfile — name/phone만 반영, deleteRefresh 미호출")
    void updateProfile_updates_name_and_phone_only_no_refresh_invalidation() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountService.updateProfile(USER_ID, "새 이름", "010-9999-8888");

        assertThat(user.getName()).isEqualTo("새 이름");
        assertThat(user.getPhone()).isEqualTo("010-9999-8888");
        // email/role/passwordHash 불변 확인
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isEqualTo(CURRENT_HASH);
        assertThat(user.getRole()).isEqualTo(Role.CONSUMER);
        // refresh 무효화 미호출 (인증 영향 없음)
        verify(refreshTokenStore, never()).deleteRefresh(USER_ID);
    }

    @Test
    @DisplayName("updateProfile — phone이 null/빈 문자열이면 null로 저장 (optional 필드)")
    void updateProfile_normalizes_phone_to_null_when_blank() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountService.updateProfile(USER_ID, "이름", "");

        assertThat(user.getPhone()).isNull();
    }

    // ============================================================
    // withdraw
    // ============================================================

    @Test
    @DisplayName("withdraw — status=WITHDRAWN 전이 + deletedAt 세팅")
    void withdraw_sets_status_to_withdrawn_and_sets_deleted_at() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountService.withdraw(USER_ID);

        assertThat(user.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("withdraw — deleteRefresh 호출 (동기화 비활성 — 직접 호출)")
    void withdraw_calls_delete_refresh_directly_when_sync_inactive() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountService.withdraw(USER_ID);

        verify(refreshTokenStore).deleteRefresh(USER_ID);
    }

    @Test
    @DisplayName("withdraw — memberRepository.delete 미호출 (물리 삭제 없음)")
    void withdraw_does_not_call_physical_delete() {
        User user = userWithId(USER_ID, EMAIL, CURRENT_HASH);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountService.withdraw(USER_ID);

        verify(memberRepository, never()).delete(user);
        verify(memberRepository, never()).deleteById(USER_ID);
    }

    // helper
    private User userWithId(long id, String email, String passwordHash) {
        User user = User.of(email, passwordHash, "홍길동", "010-1234-5678", Role.CONSUMER);
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
