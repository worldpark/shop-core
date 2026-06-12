package com.shop.shop.order.dto;

/**
 * 배송 완료(deliver) 전용 응답 래퍼 (정합5).
 *
 * <p>shipment: 갱신된 배송(status=delivered, deliveredAt 채워짐).
 * orderDelivered: 현재 주문 status가 "delivered"인지(전이 발생 여부 아님 — 멱등 재완료도 일관).
 *
 * <p>공유 DTO {@link ShipmentResponse}에 주문 단위 플래그를 넣지 않기 위해 분리(공유 DTO 오염 금지).
 * 소비자 배송 목록 각 항목에 orderDelivered가 붙으면 order.status와 중복되고 의미가 어색하다.
 *
 * @param shipment      갱신된 배송 응답 (status=delivered)
 * @param orderDelivered 현재 주문 status가 delivered인지 — 전이 발생 여부 아님(멱등 재완료 시 일관 true)
 */
public record DeliverResponse(ShipmentResponse shipment, boolean orderDelivered) {
}
