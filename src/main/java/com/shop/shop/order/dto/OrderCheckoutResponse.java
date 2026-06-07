package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문서 조회 응답 DTO (체크아웃 전용).
 *
 * <p>주문 생성 전 장바구니 기반 합성 화면용. orderId/orderNumber/status/createdAt 없음.
 * View 모델 키 {@code checkout}으로 사용.
 *
 * <p>체크아웃은 주문 생성이 아니므로 재고 락·차감·clearCart를 수행하지 않는다 — 조회·합성 전용.
 */
public record OrderCheckoutResponse(
        List<OrderItemResponse> items,
        BigDecimal itemsAmount,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal finalAmount,
        boolean hasItems
) {
}
