package com.shop.shop.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User 도메인 메서드 단위 테스트.
 * Spring 컨텍스트 없이 도메인 로직만 검증한다.
 */
class UserTest {

    private User createActiveUser() {
        User user = User.of("user@example.com", "hashed_pw", "홍길동", "010-1234-5678", Role.CONSUMER);
        setId(user, 1L);
        return user;
    }

    // ============================================================
    // of() — 기본 status=ACTIVE
    // ============================================================

    @Test
    @DisplayName("of() — 신규 생성 시 status=ACTIVE, deletedAt=null")
    void of_creates_user_with_active_status_and_null_deleted_at() {
        User user = User.of("user@example.com", "hashed_pw", "홍길동", null, Role.CONSUMER);

        assertThat(user.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(user.getDeletedAt()).isNull();
    }

    // ============================================================
    // isActive()
    // ============================================================

    @Test
    @DisplayName("isActive() — ACTIVE 상태면 true")
    void isActive_returns_true_when_active() {
        User user = createActiveUser();

        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("isActive() — WITHDRAWN 상태면 false")
    void isActive_returns_false_when_withdrawn() {
        User user = createActiveUser();
        user.withdraw();

        assertThat(user.isActive()).isFalse();
    }

    // ============================================================
    // changePassword()
    // ============================================================

    @Test
    @DisplayName("changePassword() — passwordHash가 새 값으로 갱신된다")
    void changePassword_updates_password_hash() {
        User user = createActiveUser();
        String newHash = "new_bcrypt_hash";

        user.changePassword(newHash);

        assertThat(user.getPasswordHash()).isEqualTo(newHash);
    }

    @Test
    @DisplayName("changePassword() — status/email/name/phone/role은 변경되지 않는다")
    void changePassword_does_not_affect_other_fields() {
        User user = createActiveUser();
        String originalEmail = user.getEmail();
        String originalName = user.getName();
        Role originalRole = user.getRole();
        MemberStatus originalStatus = user.getStatus();

        user.changePassword("new_hash");

        assertThat(user.getEmail()).isEqualTo(originalEmail);
        assertThat(user.getName()).isEqualTo(originalName);
        assertThat(user.getRole()).isEqualTo(originalRole);
        assertThat(user.getStatus()).isEqualTo(originalStatus);
    }

    // ============================================================
    // updateProfile()
    // ============================================================

    @Test
    @DisplayName("updateProfile() — name/phone이 새 값으로 갱신된다")
    void updateProfile_updates_name_and_phone() {
        User user = createActiveUser();

        user.updateProfile("김철수", "010-9999-8888");

        assertThat(user.getName()).isEqualTo("김철수");
        assertThat(user.getPhone()).isEqualTo("010-9999-8888");
    }

    @Test
    @DisplayName("updateProfile() — phone을 null로 설정할 수 있다 (optional 필드)")
    void updateProfile_allows_null_phone() {
        User user = createActiveUser();

        user.updateProfile("김철수", null);

        assertThat(user.getName()).isEqualTo("김철수");
        assertThat(user.getPhone()).isNull();
    }

    @Test
    @DisplayName("updateProfile() — email/role/passwordHash/status는 변경되지 않는다")
    void updateProfile_does_not_affect_other_fields() {
        User user = createActiveUser();
        String originalEmail = user.getEmail();
        String originalPasswordHash = user.getPasswordHash();
        Role originalRole = user.getRole();
        MemberStatus originalStatus = user.getStatus();

        user.updateProfile("새 이름", "010-1111-2222");

        assertThat(user.getEmail()).isEqualTo(originalEmail);
        assertThat(user.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(user.getRole()).isEqualTo(originalRole);
        assertThat(user.getStatus()).isEqualTo(originalStatus);
    }

    // ============================================================
    // withdraw()
    // ============================================================

    @Test
    @DisplayName("withdraw() — status가 WITHDRAWN으로 전이된다")
    void withdraw_sets_status_to_withdrawn() {
        User user = createActiveUser();

        user.withdraw();

        assertThat(user.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("withdraw() — deletedAt이 현재 시각으로 설정된다")
    void withdraw_sets_deleted_at_to_now() {
        User user = createActiveUser();
        Instant before = Instant.now();

        user.withdraw();

        Instant after = Instant.now();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getDeletedAt()).isAfterOrEqualTo(before);
        assertThat(user.getDeletedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("withdraw() — email/name/passwordHash/role은 변경되지 않는다")
    void withdraw_does_not_affect_other_fields() {
        User user = createActiveUser();
        String originalEmail = user.getEmail();
        String originalName = user.getName();
        String originalPasswordHash = user.getPasswordHash();
        Role originalRole = user.getRole();

        user.withdraw();

        assertThat(user.getEmail()).isEqualTo(originalEmail);
        assertThat(user.getName()).isEqualTo(originalName);
        assertThat(user.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(user.getRole()).isEqualTo(originalRole);
    }

    @Test
    @DisplayName("withdraw() — 이미 탈퇴된 상태에서 다시 호출해도 멱등 (예외 없음)")
    void withdraw_is_idempotent() {
        User user = createActiveUser();
        user.withdraw();

        // 재탈퇴 — 예외 없이 실행
        user.withdraw();

        assertThat(user.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isNotNull();
    }

    // ============================================================
    // recordLogin()
    // ============================================================

    @Test
    @DisplayName("recordLogin() — lastLoginAt이 전달된 시각으로 갱신된다")
    void recordLogin_updates_last_login_at() {
        User user = createActiveUser();
        assertThat(user.getLastLoginAt()).isNull();

        Instant loginTime = Instant.now();
        user.recordLogin(loginTime);

        assertThat(user.getLastLoginAt()).isEqualTo(loginTime);
    }

    @Test
    @DisplayName("recordLogin() — 반복 호출 시 최신 시각으로 덮어쓴다")
    void recordLogin_overwrites_with_latest_time() {
        User user = createActiveUser();

        Instant first = Instant.now().minusSeconds(60);
        user.recordLogin(first);
        assertThat(user.getLastLoginAt()).isEqualTo(first);

        Instant second = Instant.now();
        user.recordLogin(second);

        assertThat(user.getLastLoginAt()).isEqualTo(second);
    }

    @Test
    @DisplayName("recordLogin() — status/email/name/phone/role/deletedAt은 변경되지 않는다")
    void recordLogin_does_not_affect_other_fields() {
        User user = createActiveUser();
        String originalEmail = user.getEmail();
        String originalName = user.getName();
        Role originalRole = user.getRole();
        MemberStatus originalStatus = user.getStatus();

        user.recordLogin(Instant.now());

        assertThat(user.getEmail()).isEqualTo(originalEmail);
        assertThat(user.getName()).isEqualTo(originalName);
        assertThat(user.getRole()).isEqualTo(originalRole);
        assertThat(user.getStatus()).isEqualTo(originalStatus);
        assertThat(user.getDeletedAt()).isNull();
    }

    // helper
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
