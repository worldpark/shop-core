package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 이메일 중복 예외.
 *
 * <p>회원가입 시 이미 사용 중인 이메일로 가입을 시도하거나,
 * 동시성 경합으로 DB unique 제약 위반이 발생하면 이 예외로 변환된다.
 *
 * <p>REST 진입점: {@link com.shop.shop.common.exception.RestExceptionHandler}가 409 ErrorResponse JSON으로 변환.
 * View 진입점: MemberSignupViewController가 catch 후 BindingResult.rejectValue("email", ...)로 재렌더링.
 */
public class DuplicateEmailException extends BusinessException {

    public DuplicateEmailException() {
        super("이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT);
    }
}
