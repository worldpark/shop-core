package com.shop.shop.order.dto;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 주문 이행 화면 View DTO.
 *
 * <p>admin facade {@code AdminOrderFulfillmentFacade.listFulfillableOrders}의 반환 타입.
 * {@code paid}/{@code preparing} 상태의 주문과 미발송 항목, 기존 배송 현황을 담는다.
 *
 * <p>admin 감독 목적의 sellerId/sellerLabel은 노출(ROLE_ADMIN 한정),
 * 그 외 내부 식별자·Entity·로컬 경로 미노출. View 전용 scalar DTO.
 *
 * @param orderId       주문 ID
 * @param orderNumber   주문 번호
 * @param status        주문 상태 (lowercase: paid/preparing)
 * @param unshippedItems 미발송 항목 목록 (배송 생성 폼 렌더에 사용)
 * @param shipments     기존 배송 현황 목록 (배송 상태·포함 항목 표시, seller 구분 포함)
 */
public record AdminOrderFulfillmentView(
        long orderId,
        String orderNumber,
        String status,
        List<UnshippedItem> unshippedItems,
        List<AdminShipmentView> shipments
) {

    /**
     * 미발송 주문 항목 DTO (배송 생성 폼 체크박스에 사용).
     *
     * @param orderItemId 주문 항목 ID
     * @param productName 상품명 (주문 시점 스냅샷)
     * @param quantity    수량
     */
    public record UnshippedItem(
            long orderItemId,
            String productName,
            int quantity
    ) {}

    /**
     * admin 전용 배송 현황 DTO — seller 구분 정보 포함.
     *
     * <p>REST 공용 {@link ShipmentResponse}와 달리 admin 감독 목적의 sellerId/sellerLabel을 추가로 노출한다.
     * ROLE_ADMIN 화면 전용이며, REST API나 seller 화면에는 노출되지 않는다.
     *
     * <p>sellerLabel 규칙:
     * <ul>
     *   <li>sellerId non-null → 판매자명 (해석 실패 시 "판매자(#N)" fallback)</li>
     *   <li>sellerId null → "관리자 직접 처리"</li>
     * </ul>
     *
     * @param shipmentId     배송 ID
     * @param status         배송 상태 (lowercase: preparing/shipping/delivered)
     * @param sellerId       판매자 ID (nullable — admin 생성 시 null)
     * @param sellerLabel    판매자 표기 레이블 (null 불가 — 항상 문자열 반환)
     * @param carrier        택배사명 (nullable — preparing 시 null)
     * @param trackingNumber 운송장 번호 (nullable — preparing 시 null)
     * @param shippedAt      배송 시작 시각 (nullable — preparing 시 null)
     * @param deliveredAt    배송 완료 시각 (nullable — delivered 아니면 null)
     * @param items          배송 항목 목록
     */
    public record AdminShipmentView(
            long shipmentId,
            String status,
            Long sellerId,
            String sellerLabel,
            String carrier,
            String trackingNumber,
            Instant shippedAt,
            Instant deliveredAt,
            List<ShipmentItemResponse> items
    ) {}
}
