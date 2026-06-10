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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 Entity.
 *
 * <p>테이블: orders (V1__init_schema.sql)
 * <p>userId 스칼라: member Entity 직접 참조 금지(architecture-rule 모듈 경계).
 * <p>status: DB lowercase varchar('pending','paid','preparing','shipping','delivered','cancelled','refunded').
 * <p>created_at/updated_at: DB 트리거 소유 → BaseEntity 상속(읽기전용 매핑).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 및 의도 메서드 사용.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유자 userId 스칼라.
     * order → member Entity 직접 참조 금지 — Long 스칼라로 보유.
     * FK 무결성은 DB(REFERENCES users(id) ON DELETE RESTRICT)가 보장.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    /**
     * 주문 상태 (DB lowercase 문자열 — enum 아님).
     * 생성 시 "pending".
     */
    @Column(nullable = false)
    private String status;

    @Column(name = "items_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemsAmount;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "final_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalAmount;

    // 배송지 스냅샷 (주문 시점 고정, 이후 변경 불가)
    @Column(name = "ship_recipient")
    private String shipRecipient;

    @Column(name = "ship_phone")
    private String shipPhone;

    @Column(name = "ship_postcode")
    private String shipPostcode;

    @Column(name = "ship_address1")
    private String shipAddress1;

    @Column(name = "ship_address2")
    private String shipAddress2;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * 주문 생성 정적 팩토리.
     *
     * <p>discount=0/shipping=0/final=items 고정 (이번 Task 범위).
     *
     * @param userId       소유자 userId
     * @param orderNumber  주문 번호 (unique)
     * @param itemsAmount  상품 합계금액
     * @param shipRecipient 수령인
     * @param shipPhone    수령인 전화번호
     * @param shipPostcode 우편번호
     * @param shipAddress1 주소1
     * @param shipAddress2 주소2 (nullable)
     * @return 새 Order 인스턴스 (status="pending")
     */
    public static Order create(long userId, String orderNumber, BigDecimal itemsAmount,
                               String shipRecipient, String shipPhone, String shipPostcode,
                               String shipAddress1, String shipAddress2) {
        Order order = new Order();
        order.userId = userId;
        order.orderNumber = orderNumber;
        order.status = "pending";
        order.itemsAmount = itemsAmount;
        order.discountAmount = BigDecimal.ZERO;
        order.shippingFee = BigDecimal.ZERO;
        order.finalAmount = itemsAmount; // discount=0, shipping=0이므로 final=items
        order.shipRecipient = shipRecipient;
        order.shipPhone = shipPhone;
        order.shipPostcode = shipPostcode;
        order.shipAddress1 = shipAddress1;
        order.shipAddress2 = shipAddress2;
        return order;
    }

    /**
     * 주문 항목 추가 의도 메서드.
     *
     * @param item 추가할 OrderItem
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.assignOrder(this);
    }

    /**
     * 주문 확정 상태 전이 메서드 (pending → paid).
     *
     * <p>status가 "pending"인 주문만 "paid"로 전이할 수 있다.
     * "pending"이 아닌 상태에서 호출하면 IllegalStateException(도메인 불변식 위반)을 던진다.
     *
     * <p>016에서 사용. markFailed()는 017에서 추가.
     *
     * @throws IllegalStateException status가 "pending"이 아닐 때
     */
    public void markPaid() {
        if (!"pending".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 pending이 아니어서 paid로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "paid";
    }

    /**
     * 주문 취소 상태 전이 메서드 (pending → cancelled, 미결제 취소 경로).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"pending" → "cancelled" 전이</li>
     *   <li>"cancelled" 재호출 → 멱등(상태 무변경 no-op)</li>
     *   <li>그 외("paid"/이행단계/"refunded") → {@link IllegalStateException}(상위에서 차단됨, 방어적)</li>
     * </ul>
     *
     * <p>결제완료 취소 → 주문 refunded 전이는 {@link #markRefunded()} 사용(#3).
     *
     * @throws IllegalStateException status가 "pending"/"cancelled"가 아닐 때
     */
    public void markCancelled() {
        if ("cancelled".equals(this.status)) {
            // 멱등 처리 — 이미 cancelled면 no-op
            return;
        }
        if (!"pending".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 pending이 아니어서 cancelled로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "cancelled";
    }

    /**
     * 주문 환불 완료 상태 전이 메서드 (paid → refunded, 결제완료 취소 경로).
     *
     * <p>결제완료 주문 취소는 환불을 동반하므로 주문 상태도 "refunded"로 종결한다(#3 — payment.refunded와 정렬).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"paid" → "refunded" 전이</li>
     *   <li>"refunded" 재호출 → 멱등(상태 무변경 no-op)</li>
     *   <li>그 외 → {@link IllegalStateException}(상위에서 차단됨, 방어적)</li>
     * </ul>
     *
     * @throws IllegalStateException status가 "paid"/"refunded"가 아닐 때
     */
    public void markRefunded() {
        if ("refunded".equals(this.status)) {
            // 멱등 처리 — 이미 refunded면 no-op
            return;
        }
        if (!"paid".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 paid가 아니어서 refunded로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "refunded";
    }
}
