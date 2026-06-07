package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 타인 소유 또는 미존재 cartItem 접근 시 발생하는 예외 (404 존재 은닉).
 *
 * <p>소유자가 아닌 사용자 또는 미존재 cartItem에 접근하면 "없는 것처럼" 다루어
 * 리소스 존재 여부를 외부에서 구분 불가하게 한다 (ProductAccessDeniedException 선례 정렬).
 *
 * <p>소유권 위반과 미존재를 동일 예외/동일 메시지로 던져 존재 구분 불가하게 한다. 403 사용 금지.
 */
public class CartItemNotFoundException extends BusinessException {

    public CartItemNotFoundException() {
        super("장바구니 항목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
