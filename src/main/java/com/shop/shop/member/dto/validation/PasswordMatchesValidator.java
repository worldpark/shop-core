package com.shop.shop.member.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Method;

/**
 * {@link PasswordMatches} 제약 구현체.
 *
 * <p>SignupRequest(record accessor: password()/passwordConfirm())와
 * SignupForm(Getter: getPassword()/getPasswordConfirm()) 두 타입을 모두 처리한다.
 * PasswordChangeRequest/PasswordChangeForm(field="newPassword", confirmField="newPasswordConfirm")도 지원.
 *
 * <p>접근 전략: getter 우선(getXxx), 없으면 record accessor(xxx) 시도.
 * 불일치 시 {@code confirmField} 이름 필드에 위반을 보고 — View BindingResult 필드별 에러 표시 가능.
 *
 * <p>일반화 이유: 기존 하드코딩("password"/"passwordConfirm")은 다른 필드명 DTO에서
 * 두 값이 모두 null이 되어 검증이 조용히 통과(보안 결함)된다.
 * initialize()에서 어노테이션 속성을 읽어 동적으로 비교한다.
 */
public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, Object> {

    private String field;
    private String confirmField;

    @Override
    public void initialize(PasswordMatches constraintAnnotation) {
        this.field = constraintAnnotation.field();
        this.confirmField = constraintAnnotation.confirmField();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String password = extractField(value, this.field);
        String passwordConfirm = extractField(value, this.confirmField);

        if (password == null && passwordConfirm == null) {
            return true;
        }

        boolean matches = password != null && password.equals(passwordConfirm);

        if (!matches) {
            // 글로벌 에러 대신 confirmField 이름의 필드에 위반 보고 (View BindingResult 필드 에러 지원)
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(this.confirmField)
                    .addConstraintViolation();
        }

        return matches;
    }

    /**
     * getter(getXxx) 우선, 없으면 record accessor(xxx) 시도로 필드 값을 추출한다.
     *
     * @param obj       대상 객체 (SignupRequest record 또는 SignupForm class)
     * @param fieldName 필드명 (예: "password", "passwordConfirm")
     * @return 필드 값 문자열, 없으면 null
     */
    private String extractField(Object obj, String fieldName) {
        // getter 우선: getPassword, getPasswordConfirm
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method getter = obj.getClass().getMethod(getterName);
            Object result = getter.invoke(obj);
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException ignored) {
            // getter 없음 — record accessor 시도
        } catch (Exception e) {
            return null;
        }

        // record accessor: password(), passwordConfirm()
        try {
            Method accessor = obj.getClass().getMethod(fieldName);
            Object result = accessor.invoke(obj);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
