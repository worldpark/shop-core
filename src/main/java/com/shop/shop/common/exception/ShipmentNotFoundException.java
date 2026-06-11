package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 배송 미존재 예외 (404 Not Found).
 *
 * <p>발생 조건:
 * <ul>
 *   <li>shipmentId로 배송을 찾을 수 없을 때 (관리자 ship 경로)</li>
 * </ul>
 *
 * <p>OrderNotFoundException 재사용 시 "배송이 없는데 주문 없음"으로 오도되므로 별도 예외로 분리한다(개선3).
 * 관리자 경로 — 존재 은닉 불요(관리자가 직접 shipmentId를 입력하므로 404 메시지 명시).
 *
 * <p>error-response-rule: 리소스 없음 → 404. BusinessException 계층이 RestExceptionHandler에서
 * 자동으로 ErrorResponse로 매핑된다.
 */
public class ShipmentNotFoundException extends BusinessException {

    public ShipmentNotFoundException() {
        super("배송을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
