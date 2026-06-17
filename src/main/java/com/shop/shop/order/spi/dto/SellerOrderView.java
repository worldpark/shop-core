package com.shop.shop.order.spi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 판매자 주문 조회 뷰 DTO (order.spi.dto — published port, Entity 미노출).
 *
 * <p>판매자가 자기 owner_id 항목이 포함된 주문을 조회할 때 반환하는 뷰 DTO.
 * 주문 요약(orderNumber·status·createdAt·finalAmount) +
 * 해당 판매자 소유 항목만(productName·optionLabel·qty·unitPrice + 항목별 배송상태).
 * 타 판매자 항목은 포함하지 않는다(소유권 스코핑 — IDOR 방지).
 *
 * <p>배송상태는 DB lowercase: preparing/shipping/delivered.
 * 배송이 아직 생성되지 않은 항목의 shipmentStatus = null.
 *
 * <p>의존 방향: web → order.spi.dto (단방향). order는 web을 참조하지 않는다.
 */
public record SellerOrderView(
        long orderId,
        String orderNumber,
        String orderStatus,
        BigDecimal finalAmount,
        Instant createdAt,
        List<SellerOrderItemView> items
) {

    /**
     * 판매자 소유 주문 항목 뷰 (해당 판매자 항목만).
     *
     * @param orderItemId    주문 항목 ID
     * @param productName    상품명 스냅샷
     * @param optionLabel    옵션 라벨 스냅샷 (nullable)
     * @param quantity       주문 수량
     * @param unitPrice      단가 스냅샷
     * @param shipmentStatus 배송 상태 (preparing/shipping/delivered, 미생성 시 null)
     */
    public record SellerOrderItemView(
            long orderItemId,
            String productName,
            String optionLabel,
            int quantity,
            BigDecimal unitPrice,
            String shipmentStatus
    ) {}
}
