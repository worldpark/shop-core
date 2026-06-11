package com.shop.shop.order.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 배송(Shipment) 엔티티 — 주문 이행 단위.
 *
 * <p>테이블: shipments (V4__shipments.sql)
 * <p>orderId 스칼라: Order 양방향 연관을 만들지 않고 scalar FK로 보유.
 * 같은 order 모듈이라 @ManyToOne도 허용되지만, 미발송 판정·응답에 scalar로 충분하다.
 * <p>sellerId: nullable 이음매 — backlog 002(판매자 범위 이행) 준비용, 본 Task 미사용.
 * <p>carrier/trackingNumber/shippedAt/deliveredAt: nullable — 020/021에서 사용.
 * <p>created_at/updated_at: DB 트리거 소유 → BaseEntity 상속(읽기전용 매핑).
 * 트리거(trg_shipments_set_updated_at)는 V4에서 생성(모순1 — 트리거 없으면 020/021 UPDATE에서 updated_at stale).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #preparing(long)} 및 의도 메서드 사용.
 *
 * <p><b>본 Task(019)는 preparing 생성까지만 구현한다.</b>
 * markShipping/markDelivered 및 shipping/delivered 전이는 020/021 소관.
 */
@Entity
@Table(name = "shipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 주문 ID 스칼라 (FK orders.id, NOT NULL).
     * Order 엔티티 직접 참조 금지 — Long 스칼라로 보유.
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * 판매자 ID 스칼라 (nullable 이음매).
     * backlog 002에서 판매자 범위 이행을 켤 때 채운다. 본 Task 미사용.
     */
    @Column(name = "seller_id")
    private Long sellerId;

    /**
     * 배송 상태 (DB lowercase 문자열).
     * 생성 시 "preparing". shipping/delivered 전이는 020/021.
     */
    @Column(nullable = false)
    private String status;

    /** 택배사 코드 (nullable — 020에서 사용). */
    @Column
    private String carrier;

    /** 운송장 번호 (nullable — 020에서 사용). */
    @Column(name = "tracking_number")
    private String trackingNumber;

    /** 발송 시각 (nullable — 020에서 사용). */
    @Column(name = "shipped_at")
    private Instant shippedAt;

    /** 배송 완료 시각 (nullable — 021에서 사용). */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShipmentItem> items = new ArrayList<>();

    /**
     * 배송 생성 정적 팩토리 — preparing 상태.
     *
     * <p>추적 필드(carrier/trackingNumber/shippedAt/deliveredAt)는 모두 null로 초기화된다.
     * 본 Task는 preparing 생성까지만 구현한다.
     *
     * @param orderId 대상 주문 ID
     * @return 새 Shipment 인스턴스 (status="preparing")
     */
    public static Shipment preparing(long orderId) {
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.status = "preparing";
        // carrier/trackingNumber/shippedAt/deliveredAt 은 null (020/021에서 채움)
        return shipment;
    }

    /**
     * 배송 항목 추가 의도 메서드 (양방향 연관 세팅).
     *
     * <p>ShipmentItem의 shipment 역참조를 함께 설정한다.
     *
     * @param item 추가할 ShipmentItem
     */
    public void addItem(ShipmentItem item) {
        items.add(item);
        item.assignShipment(this);
    }

    // markShipping / markDelivered 는 020/021 소관 — 본 Task 미구현.
}
