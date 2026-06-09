package com.shop.shop.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 상태 뷰 DTO (주문 상세 결제 영역용).
 *
 * <p>View가 결제 폼 노출 조건과 상태 표시에 사용한다.
 * ownerId/Entity 미포함.
 *
 * @param orderId  주문 PK
 * @param status   결제 상태 ("paid"/"ready"/"none" — 결제 row 없으면 "none")
 * @param paid     결제 완료 여부 (status == "paid")
 * @param payable  결제 가능 여부 (= 주문 pending && !paid) — 결제 폼 노출 조건
 * @param amount   결제 금액 (결제 row 없으면 주문 finalAmount 또는 null)
 * @param paidAt   결제 완료 시각 (nullable, 미결제 시 null)
 */
public record PaymentStatusView(
        long orderId,
        String status,
        boolean paid,
        boolean payable,
        BigDecimal amount,
        Instant paidAt
) {}
