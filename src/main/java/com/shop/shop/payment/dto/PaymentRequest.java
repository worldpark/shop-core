package com.shop.shop.payment.dto;

import java.math.BigDecimal;

/**
 * 결제 요청 DTO (payment 소유).
 *
 * <p>web 타입({@code OrderPaymentForm})을 받지 않는다(#1, architecture-rule).
 * web 핸들러가 {@code OrderPaymentForm → PaymentRequest}로 변환한 뒤 facade/REST에 전달한다.
 *
 * @param method  결제 수단 (선택, null 또는 빈 문자열이면 기본 "mock" 사용)
 * @param amount  결제 금액 (선택 — 전달 시 주문 finalAmount와 일치 검증, 불일치 시 400)
 */
public record PaymentRequest(
        String method,
        BigDecimal amount
) {

    /** method가 null/빈 문자열이면 기본값 "mock"을 반환하는 편의 메서드. */
    public String methodOrDefault() {
        return (method != null && !method.isBlank()) ? method : "mock";
    }
}
