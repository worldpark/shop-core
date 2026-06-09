package com.shop.shop.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 응답 DTO (REST API / View facade 공용).
 *
 * <p>ownerId/Entity/로컬 경로 미포함.
 * status는 DB lowercase 문자열("paid"/"ready").
 * 멱등 재요청(이미 paid)은 기존 결제 결과를 200으로 반환 — 이 DTO 그대로 반환.
 *
 * @param paymentId       결제 PK
 * @param orderId         주문 PK
 * @param orderNumber     주문 번호
 * @param status          결제 상태 (lowercase: "paid"/"ready")
 * @param method          결제 수단
 * @param amount          결제 금액
 * @param pgTransactionId PG 거래 번호 (승인 시 non-null)
 * @param paidAt          결제 완료 시각 (승인 시 non-null)
 */
public record PaymentResponse(
        long paymentId,
        long orderId,
        String orderNumber,
        String status,
        String method,
        BigDecimal amount,
        String pgTransactionId,
        Instant paidAt
) {}
