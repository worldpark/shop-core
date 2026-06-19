package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 최초 ADMIN 부트스트랩 시 이미 ADMIN이 존재하는 경우 발생하는 예외.
 *
 * <p>부트스트랩 화면은 ADMIN이 0명인 경우에만 열린다.
 * POST /setup/admin 요청 시 트랜잭션 내 {@code countByRole(ADMIN) > 0}이면 이 예외를 던진다.
 *
 * <p>View 진입점: AdminSetupViewController가 catch 후 redirect:/login (화면 폐쇄, 정보 최소 노출).
 * REST 진입점 없음 (REST API는 ADMIN 생성 경로를 노출하지 않는다).
 */
public class AdminAlreadyExistsException extends BusinessException {

    public AdminAlreadyExistsException() {
        super("이미 관리자 계정이 존재합니다.", HttpStatus.CONFLICT);
    }
}
