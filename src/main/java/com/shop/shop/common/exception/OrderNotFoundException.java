package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 미존재·타인 주문 접근 예외 (404 존재 은닉).
 *
 * <p>타인 소유 주문과 미존재 주문을 동일 예외·동일 메시지로 처리해 존재 구분 불가하게 한다.
 * ProductAccessDeniedException 선례 — 403 미사용.
 */
public class OrderNotFoundException extends BusinessException {

    public OrderNotFoundException() {
        super("주문을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
