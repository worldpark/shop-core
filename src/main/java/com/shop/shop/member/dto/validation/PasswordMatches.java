package com.shop.shop.member.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 일치 교차검증 제약 어노테이션.
 *
 * <p>클래스 레벨에 선언한다. SignupRequest(record)·SignupForm(class) 양쪽에 적용 가능.
 * 불일치 시 {@code confirmField} 이름의 필드에 위반이 보고된다 — View BindingResult와 REST 400 모두 통일 처리.
 *
 * <p>기존 SignupRequest/SignupForm은 default(field="password", confirmField="passwordConfirm")로 무변경.
 * 비밀번호 변경 DTO는 {@code @PasswordMatches(field="newPassword", confirmField="newPasswordConfirm")}으로 적용.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordMatchesValidator.class)
public @interface PasswordMatches {

    String message() default "비밀번호가 일치하지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * 비교 기준 필드명 (비밀번호).
     * 기본값: "password" — SignupRequest/SignupForm 무변경.
     */
    String field() default "password";

    /**
     * 확인 필드명 (비밀번호 확인).
     * 기본값: "passwordConfirm" — SignupRequest/SignupForm 무변경.
     * 불일치 시 이 필드에 제약 위반이 보고된다.
     */
    String confirmField() default "passwordConfirm";
}
