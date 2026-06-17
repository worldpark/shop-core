package com.shop.shop.order.spi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 판매자 주문 조회 뷰 DTO (order.spi.dto — published port, Entity 미노출).
 *
 * <p>판매자가 자기 owner_id 항목이 포함된 주문을 조회할 때 반환하는 뷰 DTO.
 * 주문 요약(orderNumber·status·createdAt·finalAmount) +
 * 해당 판매자 소유 항목만(productName·optionLabel·qty·unitPrice + 항목별 배송상태·shipmentId).
 * 타 판매자 항목은 포함하지 않는다(소유권 스코핑 — IDOR 방지).
 *
 * <p>Phase 2(049) 추가:
 * <ul>
 *   <li>{@code sellerShipments} — 이 판매자 소유 shipment 그루핑(shipmentId·status·소속 항목)</li>
 *   <li>{@code unshippedOwnedItems} — 미발송 owned 항목 목록 (배송 생성 폼 노출용)</li>
 * </ul>
 *
 * <p>배송상태는 DB lowercase: preparing/shipping/delivered.
 * 배송이 아직 생성되지 않은 항목의 shipmentStatus = null, shipmentId = null.
 *
 * <p>의존 방향: web → order.spi.dto (단방향). order는 web을 참조하지 않는다.
 */
public record SellerOrderView(
        long orderId,
        String orderNumber,
        String orderStatus,
        BigDecimal finalAmount,
        Instant createdAt,
        List<SellerOrderItemView> items,
        List<SellerShipmentView> sellerShipments,
        List<UnshippedOwnedItem> unshippedOwnedItems
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
     * @param shipmentId     배송 ID (미생성 시 null)
     */
    public record SellerOrderItemView(
            long orderItemId,
            String productName,
            String optionLabel,
            int quantity,
            BigDecimal unitPrice,
            String shipmentStatus,
            Long shipmentId
    ) {}

    /**
     * 이 판매자 소유 배송 뷰 (Phase 2 — 배송 시작/완료 폼 노출용).
     *
     * @param shipmentId  배송 ID (POST /seller/shipments/{id}/ship·/deliver 대상)
     * @param status      배송 상태 (preparing/shipping/delivered)
     * @param orderItemIds 이 배송에 속한 주문 항목 ID 목록
     */
    public record SellerShipmentView(
            long shipmentId,
            String status,
            List<Long> orderItemIds
    ) {}

    /**
     * 미발송 owned 항목 뷰 (배송 생성 폼 체크박스에 사용).
     *
     * @param orderItemId 주문 항목 ID
     * @param productName 상품명 (주문 시점 스냅샷)
     * @param quantity    수량
     */
    public record UnshippedOwnedItem(
            long orderItemId,
            String productName,
            int quantity
    ) {}
}
