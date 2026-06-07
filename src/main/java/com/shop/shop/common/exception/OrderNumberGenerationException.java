package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 번호 생성 3회 재시도 초과 예외 (500).
 *
 * <p>orderNumber unique 충돌이 3회 초과할 경우 발생하는 운영 이상 상황.
 * 무한 재시도를 명시적으로 금지하기 위해 별도 예외로 분리한다.
 */
public class OrderNumberGenerationException extends BusinessException {

    public OrderNumberGenerationException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public OrderNumberGenerationException() {
        super("주문 번호 생성에 실패했습니다. 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
