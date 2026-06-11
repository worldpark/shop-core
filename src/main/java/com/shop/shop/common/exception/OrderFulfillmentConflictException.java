package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 이행 상태 충돌 예외 (409 Conflict).
 *
 * <p>발생 조건:
 * <ul>
 *   <li>주문이 {@code paid}/{@code preparing}이 아닌 상태에서 배송 생성 시도</li>
 *   <li>미발송 항목이 0건(만들 배송이 없음)</li>
 *   <li>이미 다른 배송에 배정된 항목을 지정</li>
 * </ul>
 *
 * <p><b>부작용 발생 전 판정(모순2)</b>: OrderFulfillmentService에서 Shipment INSERT·rollup 전에
 * 상태/항목을 검증해 던진다. 롤백할 부작용이 없다.
 *
 * <p>입력 오류(지정 orderItemId가 미존재/타 주문 소속)는 {@link InvalidShipmentItemException}(400) 사용(모순3).
 *
 * <p>error-response-rule: 상태 충돌 → 409. BusinessException 계층이 RestExceptionHandler에서
 * 자동으로 ErrorResponse로 매핑된다.
 *
 * @see OrderCancellationConflictException 동형 선례
 */
public class OrderFulfillmentConflictException extends BusinessException {

    public OrderFulfillmentConflictException() {
        super("주문 상태 충돌로 배송을 생성할 수 없습니다.", HttpStatus.CONFLICT);
    }

    public OrderFulfillmentConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
