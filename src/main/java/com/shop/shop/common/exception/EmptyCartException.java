package com.shop.shop.common.exception;

/**
 * 빈 장바구니 주문 시도 예외 (400 Bad Request).
 *
 * <p>주문 생성 시 장바구니가 비어 있을 때 발생한다.
 * 재고/구매가능 상태 충돌이 아닌 입력 단계 실패이므로 400.
 */
public class EmptyCartException extends BusinessException {

    public EmptyCartException(String message) {
        super(message);
    }

    public EmptyCartException() {
        super("장바구니가 비어 있습니다.");
    }
}
