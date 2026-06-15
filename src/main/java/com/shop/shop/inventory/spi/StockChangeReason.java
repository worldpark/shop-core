package com.shop.shop.inventory.spi;

/**
 * 재고 변동 사유 (inventory SPI 노출 enum).
 *
 * <p>포트({@link InventoryStockPort})를 통해 호출자(order/product)에게 노출되는
 * 비-primitive 타입. 변동 분류는 재고 도메인의 개념이므로 inventory가 SSOT.
 * Spring Modulith @NamedInterface("spi")로 노출된 패키지에 배치하여
 * order/product 모듈이 직접 참조할 수 있다(inventory.domain 패키지 참조 금지 우회).
 *
 * <ul>
 *   <li>{@code ORDER_DECREASE} — 주문 생성 시 재고 차감</li>
 *   <li>{@code CANCEL_RESTORE} — 사용자 취소로 인한 재고 복원</li>
 *   <li>{@code EXPIRY_RESTORE} — 결제 만료로 인한 재고 복원</li>
 *   <li>{@code ADJUSTMENT} — 운영자 실사·손실·손상 보정</li>
 * </ul>
 */
public enum StockChangeReason {
    ORDER_DECREASE,
    CANCEL_RESTORE,
    EXPIRY_RESTORE,
    ADJUSTMENT
}
