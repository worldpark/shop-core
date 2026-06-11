package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 배송 항목 입력 오류 예외 (400 Bad Request).
 *
 * <p>발생 조건:
 * <ul>
 *   <li>지정한 {@code orderItemId}가 존재하지 않음</li>
 *   <li>지정한 {@code orderItemId}가 해당 주문 소속이 아님 (다른 주문의 항목)</li>
 * </ul>
 *
 * <p>상태 충돌(409)과 분리(모순3):
 * <ul>
 *   <li>입력 오류(요청 자체의 유효성 문제) = 400 → 이 예외</li>
 *   <li>상태 충돌(현재 리소스 상태와의 충돌) = 409 → {@link OrderFulfillmentConflictException}</li>
 * </ul>
 *
 * <p>error-response-rule: 입력 오류 → 400. BusinessException 계층이 RestExceptionHandler에서
 * 자동으로 ErrorResponse로 매핑된다.
 */
public class InvalidShipmentItemException extends BusinessException {

    public InvalidShipmentItemException() {
        super("지정한 배송 항목이 유효하지 않습니다.", HttpStatus.BAD_REQUEST);
    }

    public InvalidShipmentItemException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
