package com.shop.shop.payment.dto;

/**
 * 주문 취소 REST 200 응답 DTO + View facade 반환 타입.
 *
 * <p>Entity·내부 ID 미노출. 취소/환불 결과를 클라이언트에 전달한다.
 *
 * @param orderId         주문 PK
 * @param orderNumber     주문 번호
 * @param orderStatus     취소 후 주문 상태 ("cancelled" 또는 "refunded")
 * @param refunded        환불 여부 (결제완료 취소=true, 미결제 취소=false)
 * @param refundedAmount  환불 금액 (long, KRW=원). 미결제 취소=0
 * @param currency        통화 코드 (예: "KRW")
 */
public record OrderCancelResponse(
        long orderId,
        String orderNumber,
        String orderStatus,
        boolean refunded,
        long refundedAmount,
        String currency
) {}
