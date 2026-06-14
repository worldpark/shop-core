package com.shop.shop.member.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordMatchesValidator 일반화 검증 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>@PasswordMatches(field="newPassword", confirmField="newPasswordConfirm") 일반화 후
 *       confirm 불일치가 실제로 newPasswordConfirm 필드 위반으로 보고됨 (무음 통과 회귀 차단)</li>
 *   <li>@PasswordMatches default(password/passwordConfirm) — SignupRequest/SignupForm 회귀 그린</li>
 *   <li>PasswordChangeRequest 검증 규칙 (NotBlank, Size)</li>
 *   <li>PasswordChangeForm 검증 규칙</li>
 * </ul>
 */
class PasswordChangeValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ============================================================
    // PasswordChangeRequest — @PasswordMatches(field="newPassword", confirmField="newPasswordConfirm")
    // ============================================================

    @Test
    @DisplayName("PasswordChangeRequest — 정상 입력 → 위반 없음")
    void passwordChangeRequest_valid_input_no_violations() {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "newPassword1");

        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("PasswordChangeRequest — newPassword/newPasswordConfirm 불일치 → newPasswordConfirm 필드 위반")
    void passwordChangeRequest_password_mismatch_reported_on_newPasswordConfirm_field() {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "differentPassword1");

        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("newPasswordConfirm"));
        // 기존 하드코딩("passwordConfirm") 필드에는 보고되지 않아야 한다 (일반화 확인)
        assertThat(violations).noneMatch(v ->
                v.getPropertyPath().toString().equals("passwordConfirm"));
    }

    @Test
    @DisplayName("PasswordChangeRequest — newPassword 길이 미달(@Size) → 위반")
    void passwordChangeRequest_new_password_too_short() {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "short", "short");

        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("newPassword"));
    }

    @Test
    @DisplayName("PasswordChangeRequest — currentPassword 빈 문자열 → @NotBlank 위반")
    void passwordChangeRequest_blank_current_password() {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "", "newPassword1", "newPassword1");

        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("currentPassword"));
    }

    @Test
    @DisplayName("PasswordChangeRequest — newPasswordConfirm 빈 문자열 → @NotBlank 위반")
    void passwordChangeRequest_blank_confirm_password() {
        PasswordChangeRequest request = new PasswordChangeRequest(
                "currentPass1", "newPassword1", "");

        Set<ConstraintViolation<PasswordChangeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("newPasswordConfirm"));
    }

    // ============================================================
    // PasswordChangeForm — @PasswordMatches(field="newPassword", confirmField="newPasswordConfirm")
    // ============================================================

    @Test
    @DisplayName("PasswordChangeForm — 정상 입력 → 위반 없음")
    void passwordChangeForm_valid_input_no_violations() {
        PasswordChangeForm form = buildForm("currentPass1", "newPassword1", "newPassword1");

        Set<ConstraintViolation<PasswordChangeForm>> violations = validator.validate(form);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("PasswordChangeForm — newPassword/newPasswordConfirm 불일치 → newPasswordConfirm 필드 위반")
    void passwordChangeForm_password_mismatch_reported_on_newPasswordConfirm_field() {
        PasswordChangeForm form = buildForm("currentPass1", "newPassword1", "differentPassword1");

        Set<ConstraintViolation<PasswordChangeForm>> violations = validator.validate(form);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("newPasswordConfirm"));
    }

    // ============================================================
    // 회귀 — SignupRequest default(password/passwordConfirm) 무변경 확인
    // ============================================================

    @Test
    @DisplayName("SignupRequest(default) — password/passwordConfirm 불일치 → passwordConfirm 필드 위반(회귀 그린)")
    void signupRequest_default_password_confirm_mismatch_still_works() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "password123", "differentpassword", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("passwordConfirm"));
    }

    @Test
    @DisplayName("SignupForm(default) — password/passwordConfirm 불일치 → passwordConfirm 필드 위반(회귀 그린)")
    void signupForm_default_password_confirm_mismatch_still_works() {
        SignupForm form = new SignupForm();
        form.setEmail("user@example.com");
        form.setPassword("password123");
        form.setPasswordConfirm("differentpassword");
        form.setName("홍길동");

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("passwordConfirm"));
    }

    // helper
    private PasswordChangeForm buildForm(String current, String newPw, String confirmPw) {
        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword(current);
        form.setNewPassword(newPw);
        form.setNewPasswordConfirm(confirmPw);
        return form;
    }
}
