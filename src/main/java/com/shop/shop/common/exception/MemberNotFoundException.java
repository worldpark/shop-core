package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 대상 회원을 찾을 수 없을 때 발생하는 예외.
 * HTTP 404 Not Found.
 */
public class MemberNotFoundException extends BusinessException {

    public MemberNotFoundException(long memberId) {
        super("회원을 찾을 수 없습니다. id=" + memberId, HttpStatus.NOT_FOUND);
    }

    public MemberNotFoundException(String email) {
        super("회원을 찾을 수 없습니다. email=" + email, HttpStatus.NOT_FOUND);
    }
}
