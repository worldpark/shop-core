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
     * 배송 생성 정적 팩토리 — preparing 상태 (admin 경로, seller_id=null).
     *
     * <p>추적 필드(carrier/trackingNumber/shippedAt/deliveredAt)는 모두 null로 초기화된다.
     *
     * @param orderId 대상 주문 ID
     * @return 새 Shipment 인스턴스 (status="preparing", seller_id=null)
     */
    public static Shipment preparing(long orderId) {
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.status = "preparing";
        // sellerId = null (admin 경로 — 판매자 스탬프 없음)
        return shipment;
    }

    /**
     * 배송 생성 정적 팩토리 — preparing 상태 + seller_id 스탬프 (판매자 경로).
     *
     * <p>판매자가 자기 소유 항목으로 배송을 생성할 때 사용한다.
     * seller_id를 스탬프해 admin 생성 배송(seller_id=null)과 구분한다.
     *
     * @param orderId  대상 주문 ID
     * @param sellerId 판매자 ID (요청 판매자 스탬프)
     * @return 새 Shipment 인스턴스 (status="preparing", seller_id=sellerId)
     */
    public static Shipment preparing(long orderId, long sellerId) {
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.sellerId = sellerId;
        shipment.status = "preparing";
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

    /**
     * 배송 시작 상태 전이 메서드 (preparing → shipping).
     *
     * <p>status가 "preparing"인 배송만 "shipping"으로 전이할 수 있다.
     * carrier/trackingNumber/shippedAt을 기록한다.
     *
     * <p>멱등 책임은 서비스가 소유한다. 도메인 메서드는 단일 전이(preparing→shipping)만 허용하며,
     * 이미 "shipping"인 배송에서 호출하면 IllegalStateException을 던진다.
     * 서비스는 shipment가 "preparing"일 때만 이 메서드를 호출한다(정합3).
     *
     * @param carrier        택배사명
     * @param trackingNumber 운송장 번호
     * @param shippedAt      배송 시작 시각
     * @throws IllegalStateException status가 "preparing"이 아닐 때
     */
    public void markShipping(String carrier, String trackingNumber, Instant shippedAt) {
        if (!"preparing".equals(this.status)) {
            throw new IllegalStateException(
                    "배송 상태가 preparing이 아니어서 shipping으로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "shipping";
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = shippedAt;
    }

    /**
     * 배송 완료 상태 전이 메서드 (shipping → delivered).
     *
     * <p>status가 "shipping"인 배송만 "delivered"로 전이할 수 있다.
     * deliveredAt을 기록한다.
     *
     * <p>멱등 책임은 서비스가 소유한다. 도메인 메서드는 단일 전이(shipping→delivered)만 허용하며,
     * "shipping"이 아닌 상태(preparing/delivered 재호출/역방향)에서 호출하면 IllegalStateException을 던진다.
     * 서비스는 shipment가 "shipping"일 때만 이 메서드를 호출한다(정합4 — 멱등 아님).
     *
     * @param deliveredAt 배송 완료 시각
     * @throws IllegalStateException status가 "shipping"이 아닐 때
     */
    public void markDelivered(Instant deliveredAt) {
        if (!"shipping".equals(this.status)) {
            throw new IllegalStateException(
                    "배송 상태가 shipping이 아니어서 delivered로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "delivered";
        this.deliveredAt = deliveredAt;
    }
}
