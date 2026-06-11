package com.shop.shop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송 항목(ShipmentItem) 엔티티 — 배송(Shipment)과 주문 항목(OrderItem) 간 매핑.
 *
 * <p>테이블: shipment_items (V4__shipments.sql)
 * <p>orderItemId 스칼라: order_items.id FK (UNIQUE — 한 주문 항목은 최대 1개 배송에만 속한다).
 * 동시 createShipment의 이중 배정을 DB 레벨에서 차단한다.
 * <p>updated_at 컬럼 없음 (불변·append) → BaseEntity 미상속 (OrderItem 선례와 동일).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #of(long)} 사용.
 */
@Entity
@Table(name = "shipment_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소속 배송 (양방향 — Shipment.addItem에서 세팅).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /**
     * 주문 항목 ID 스칼라 (FK order_items.id, UNIQUE).
     * UNIQUE 제약이 동시 이중 배정을 DB 레벨에서 차단한다.
     */
    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    /**
     * 배송 항목 생성 정적 팩토리.
     *
     * <p>shipment 역참조는 {@link Shipment#addItem(ShipmentItem)}에서 {@link #assignShipment}를 통해 세팅된다.
     *
     * @param orderItemId 대상 주문 항목 ID
     * @return 새 ShipmentItem 인스턴스
     */
    public static ShipmentItem of(long orderItemId) {
        ShipmentItem item = new ShipmentItem();
        item.orderItemId = orderItemId;
        return item;
    }

    /**
     * 소속 배송 설정 (Shipment.addItem에서 호출 — package-private).
     */
    void assignShipment(Shipment shipment) {
        this.shipment = shipment;
    }
}
