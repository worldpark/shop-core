package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * BigDecimal → long 금액 변환 실패 예외 (500 Internal Server Error).
 *
 * <p>longValueExact() 호출 시 소수부가 0이 아니거나 long 범위 초과 시 발생.
 * 정상 사용자 입력 오류가 아닌 시스템 불변식 위반이므로 500으로 응답한다(P3).
 *
 * <p>KRW는 소수 단위가 없으므로 DB numeric(12,2) 값의 소수부는 0(.00)이어야 한다.
 * 위반 시 전체 트랜잭션 롤백 — payments/주문/이벤트 부분 반영 없음.
 *
 * <p>error-response-rule: 서버 오류 → 500. 내부 스택트레이스·ArithmeticException 미노출.
 */
public class AmountConversionException extends BusinessException {

    public AmountConversionException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public AmountConversionException() {
        super("금액 변환에 실패했습니다. 시스템 관리자에게 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
