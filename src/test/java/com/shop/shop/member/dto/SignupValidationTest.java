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
 * SignupRequest(record)·SignupForm(class) Bean Validation 단위 테스트.
 * Validator 직접 사용 — Spring 컨텍스트 불필요.
 */
class SignupValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ============================================================
    // SignupRequest (record) 검증
    // ============================================================

    @Test
    @DisplayName("SignupRequest — 정상 입력 → 위반 없음")
    void signupRequest_valid_input_no_violations() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "password123", "password123", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("SignupRequest — 잘못된 이메일 형식 → @Email 위반")
    void signupRequest_invalid_email_format() {
        SignupRequest request = new SignupRequest(
                "not-an-email", "password123", "password123", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    @DisplayName("SignupRequest — 비밀번호 최소 길이(@Size) 위반")
    void signupRequest_password_too_short() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "short", "short", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("SignupRequest — password/passwordConfirm 불일치 → @PasswordMatches 위반 (passwordConfirm 필드)")
    void signupRequest_password_mismatch() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "password123", "differentpassword", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("passwordConfirm"));
    }

    @Test
    @DisplayName("SignupRequest — email 빈 문자열 → @NotBlank 위반")
    void signupRequest_blank_email() {
        SignupRequest request = new SignupRequest(
                "", "password123", "password123", "홍길동", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    @DisplayName("SignupRequest — name 빈 문자열 → @NotBlank 위반")
    void signupRequest_blank_name() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "password123", "password123", "", null);

        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    // ============================================================
    // SignupForm (class) 검증 — 동일 검증 규칙 확인
    // ============================================================

    @Test
    @DisplayName("SignupForm — 정상 입력 → 위반 없음")
    void signupForm_valid_input_no_violations() {
        SignupForm form = buildForm("user@example.com", "password123", "password123", "홍길동", null);

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("SignupForm — 잘못된 이메일 형식 → @Email 위반")
    void signupForm_invalid_email_format() {
        SignupForm form = buildForm("not-an-email", "password123", "password123", "홍길동", null);

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    @DisplayName("SignupForm — 비밀번호 최소 길이(@Size) 위반")
    void signupForm_password_too_short() {
        SignupForm form = buildForm("user@example.com", "short", "short", "홍길동", null);

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("SignupForm — password/passwordConfirm 불일치 → @PasswordMatches 위반 (passwordConfirm 필드)")
    void signupForm_password_mismatch() {
        SignupForm form = buildForm("user@example.com", "password123", "differentpassword", "홍길동", null);

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().contains("passwordConfirm"));
    }

    @Test
    @DisplayName("SignupForm — SignupRequest와 동일한 검증 규칙 적용 확인")
    void signupForm_same_validation_rules_as_signup_request() {
        // 동일한 유효 입력
        SignupForm form = buildForm("user@example.com", "password123", "password123", "홍길동", "010-1234-5678");

        Set<ConstraintViolation<SignupForm>> violations = validator.validate(form);

        assertThat(violations).isEmpty();
    }

    // helper
    private SignupForm buildForm(String email, String password, String passwordConfirm, String name, String phone) {
        SignupForm form = new SignupForm();
        form.setEmail(email);
        form.setPassword(password);
        form.setPasswordConfirm(passwordConfirm);
        form.setName(name);
        form.setPhone(phone);
        return form;
    }
}
