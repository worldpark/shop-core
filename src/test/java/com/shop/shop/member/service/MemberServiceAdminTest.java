package com.shop.shop.member.service;

import com.shop.shop.common.exception.MemberNotFoundException;
import com.shop.shop.common.exception.RoleChangeNotAllowedException;
import com.shop.shop.member.domain.Role;
import com.shop.shop.member.domain.User;
import com.shop.shop.member.repository.MemberRepository;
import com.shop.shop.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberService 관리자 기능 단위 테스트.
 * Mockito로 MemberRepository, RefreshTokenStore mock.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceAdminTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PasswordEncoder passwordEncoder;
    private MemberService memberService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        memberService = new MemberService(memberRepository, passwordEncoder, refreshTokenStore, eventPublisher);
        // 트랜잭션 동기화 매니저 초기화 (afterCommit 등록을 위해 활성화)
        TransactionSynchronizationManager.initSynchronization();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ============================================================
    // searchMembers
    // ============================================================

    @Test
    @DisplayName("searchMembers — keyword/role 조합으로 repository.search 위임 및 파라미터 전달 검증")
    void searchMembers_delegates_to_repository_with_correct_params() {
        // given
        String keyword = "kim";
        Role role = Role.SELLER;
        Pageable pageable = PageRequest.of(0, 10);

        User user = User.of("kim@example.com", "hash", "김테스터", null, Role.SELLER);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(memberRepository.search(eq(keyword), eq(role), eq(pageable))).thenReturn(page);

        // when
        Page<User> result = memberService.searchMembers(keyword, role, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        verify(memberRepository).search(keyword, role, pageable);
    }

    @Test
    @DisplayName("searchMembers — keyword null, role null 시 전체 조회 위임")
    void searchMembers_with_null_params_delegates_to_repository() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(memberRepository.search(null, null, pageable)).thenReturn(emptyPage);

        // when
        Page<User> result = memberService.searchMembers(null, null, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        verify(memberRepository).search(null, null, pageable);
    }

    // ============================================================
    // changeRole 성공
    // ============================================================

    @Test
    @DisplayName("changeRole 성공 — CONSUMER → SELLER 변경 시 role 갱신 및 afterCommit에 deleteRefresh 등록")
    void changeRole_success_updates_role_and_registers_deleteRefresh_afterCommit() {
        // given
        long adminUserId = 1L;
        long targetId = 2L;
        User admin = userWithId(1L, "admin@example.com", Role.ADMIN);
        User target = userWithId(2L, "target@example.com", Role.CONSUMER);

        when(memberRepository.findById(targetId)).thenReturn(Optional.of(target));

        // when
        memberService.changeRole(adminUserId, targetId, Role.SELLER);

        // then: role 변경 확인
        assertThat(target.getRole()).isEqualTo(Role.SELLER);

        // afterCommit에 deleteRefresh가 등록됐는지 확인
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isNotEmpty();
        // afterCommit 시뮬레이션
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(refreshTokenStore).deleteRefresh(targetId);
    }

    @Test
    @DisplayName("changeRole 성공 — ADMIN 회원이 다른 사용자를 SELLER → CONSUMER로 변경")
    void changeRole_admin_changes_seller_to_consumer() {
        // given
        long adminUserId = 1L;
        long targetId = 3L;
        User target = userWithId(3L, "seller@example.com", Role.SELLER);

        when(memberRepository.findById(targetId)).thenReturn(Optional.of(target));

        // when
        memberService.changeRole(adminUserId, targetId, Role.CONSUMER);

        // then
        assertThat(target.getRole()).isEqualTo(Role.CONSUMER);
    }

    // ============================================================
    // changeRole 실패 — 대상 없음 (404)
    // ============================================================

    @Test
    @DisplayName("changeRole 실패 — 대상 회원 없음 → MemberNotFoundException(404), role 미변경, deleteRefresh 미호출")
    void changeRole_fails_when_target_not_found() {
        // given
        when(memberRepository.findById(999L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> memberService.changeRole(1L, 999L, Role.SELLER))
                .isInstanceOf(MemberNotFoundException.class)
                .satisfies(e -> assertThat(((MemberNotFoundException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(refreshTokenStore, never()).deleteRefresh(any(Long.class));
    }

    // ============================================================
    // changeRole 실패 — ADMIN 승격 거부 (400)
    // ============================================================

    @Test
    @DisplayName("changeRole 실패 — newRole=ADMIN → RoleChangeNotAllowedException(400), role 미변경, deleteRefresh 미호출")
    void changeRole_fails_when_newRole_is_ADMIN() {
        // given
        long targetId = 2L;
        User target = userWithId(2L, "user@example.com", Role.CONSUMER);
        when(memberRepository.findById(targetId)).thenReturn(Optional.of(target));

        // when / then
        assertThatThrownBy(() -> memberService.changeRole(1L, targetId, Role.ADMIN))
                .isInstanceOf(RoleChangeNotAllowedException.class)
                .satisfies(e -> assertThat(((RoleChangeNotAllowedException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(target.getRole()).isEqualTo(Role.CONSUMER); // 미변경
        verify(refreshTokenStore, never()).deleteRefresh(any(Long.class));
    }

    // ============================================================
    // changeRole 실패 — 자기 ADMIN 강등 (409)
    // ============================================================

    @Test
    @DisplayName("changeRole 실패 — 자기 ADMIN 강등 → RoleChangeNotAllowedException(409), 미변경, deleteRefresh 미호출")
    void changeRole_fails_when_self_admin_demotion() {
        // given
        long adminUserId = 1L;
        long targetId = 1L; // 자기 자신
        User admin = userWithId(1L, "admin@example.com", Role.ADMIN);
        when(memberRepository.findById(targetId)).thenReturn(Optional.of(admin));

        // when / then
        assertThatThrownBy(() -> memberService.changeRole(adminUserId, targetId, Role.CONSUMER))
                .isInstanceOf(RoleChangeNotAllowedException.class)
                .satisfies(e -> assertThat(((RoleChangeNotAllowedException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("본인의 ADMIN 권한은 변경할 수 없습니다.");

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN); // 미변경
        verify(refreshTokenStore, never()).deleteRefresh(any(Long.class));
    }

    // ============================================================
    // changeRole 실패 — 마지막 ADMIN 강등 (409)
    // ============================================================

    @Test
    @DisplayName("changeRole 실패 — 마지막 ADMIN 강등 → RoleChangeNotAllowedException(409), 미변경, deleteRefresh 미호출")
    void changeRole_fails_when_last_admin_demotion() {
        // given
        long adminUserId = 1L;
        long targetId = 2L; // 다른 ADMIN
        User admin = userWithId(2L, "admin2@example.com", Role.ADMIN);
        when(memberRepository.findById(targetId)).thenReturn(Optional.of(admin));
        when(memberRepository.countByRole(Role.ADMIN)).thenReturn(1L); // 마지막 ADMIN

        // when / then
        assertThatThrownBy(() -> memberService.changeRole(adminUserId, targetId, Role.CONSUMER))
                .isInstanceOf(RoleChangeNotAllowedException.class)
                .satisfies(e -> assertThat(((RoleChangeNotAllowedException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("마지막 ADMIN 권한은 변경할 수 없습니다.");

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN); // 미변경
        verify(refreshTokenStore, never()).deleteRefresh(any(Long.class));
    }

    // helpers

    private User userWithId(long id, String email, Role role) {
        User user = User.of(email, "hash", "테스터", null, role);
        setId(user, id);
        return user;
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
}
