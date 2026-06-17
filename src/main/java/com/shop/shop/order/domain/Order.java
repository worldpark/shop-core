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
import org.hibernate.annotations.BatchSize;

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

    // @BatchSize: 판매자 주문 목록 등 페이지 쿼리 후 items 컬렉션 접근 시 IN 배치로 일괄 로딩(N+1 회피).
    // OrderItem.optionValues의 @BatchSize 선례와 동형.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * 주문 생성 정적 팩토리 (8-arg, 기존 시그니처 유지 — discount=ZERO 위임).
     *
     * <p>기존 호출부·테스트 회귀 0. 9-arg 오버로드에 discountAmount=ZERO 위임.
     *
     * @param userId       소유자 userId
     * @param orderNumber  주문 번호 (unique)
     * @param itemsAmount  상품 합계금액
     * @param shipRecipient 수령인
     * @param shipPhone    수령인 전화번호
     * @param shipPostcode 우편번호
     * @param shipAddress1 주소1
     * @param shipAddress2 주소2 (nullable)
     * @return 새 Order 인스턴스 (status="pending", discount=ZERO)
     */
    public static Order create(long userId, String orderNumber, BigDecimal itemsAmount,
                               String shipRecipient, String shipPhone, String shipPostcode,
                               String shipAddress1, String shipAddress2) {
        return create(userId, orderNumber, itemsAmount, BigDecimal.ZERO,
                shipRecipient, shipPhone, shipPostcode, shipAddress1, shipAddress2);
    }

    /**
     * 주문 생성 정적 팩토리 (9-arg, 할인 주입 오버로드).
     *
     * <p>도메인 불변식: discountAmount ≥ 0 AND finalAmount = itemsAmount - discountAmount ≥ 0.
     * 위반 시 IllegalStateException (서비스가 사전 보장해야 함 — 방어적).
     *
     * @param userId         소유자 userId
     * @param orderNumber    주문 번호 (unique)
     * @param itemsAmount    상품 합계금액
     * @param discountAmount 할인액 (≥ 0)
     * @param shipRecipient  수령인
     * @param shipPhone      수령인 전화번호
     * @param shipPostcode   우편번호
     * @param shipAddress1   주소1
     * @param shipAddress2   주소2 (nullable)
     * @return 새 Order 인스턴스 (status="pending")
     * @throws IllegalStateException discountAmount < 0 또는 finalAmount < 0
     */
    public static Order create(long userId, String orderNumber, BigDecimal itemsAmount,
                               BigDecimal discountAmount,
                               String shipRecipient, String shipPhone, String shipPostcode,
                               String shipAddress1, String shipAddress2) {
        if (discountAmount == null || discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("discountAmount는 0 이상이어야 합니다: " + discountAmount);
        }
        BigDecimal finalAmount = itemsAmount.subtract(discountAmount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(
                    "finalAmount는 0 이상이어야 합니다: itemsAmount=" + itemsAmount
                    + ", discountAmount=" + discountAmount);
        }

        Order order = new Order();
        order.userId = userId;
        order.orderNumber = orderNumber;
        order.status = "pending";
        order.itemsAmount = itemsAmount;
        order.discountAmount = discountAmount;
        order.shippingFee = BigDecimal.ZERO;
        order.finalAmount = finalAmount;
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

    /**
     * 배송 시작 rollup — preparing → shipping (첫 배송 시작 시).
     *
     * <p>배송이 시작되었음을 주문 status에 rollup으로 반영한다.
     * 멀티 배송에서 둘째 배송 ship 시 주문이 이미 "shipping"이면
     * 서비스가 이 메서드 호출을 생략한다(정합3 — 이미 shipping이면 skip).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"preparing" → "shipping" 전이 (첫 배송 시작)</li>
     *   <li>그 외("paid"/"shipping"/이후 상태) → {@link IllegalStateException}
     *       (상위 OrderFulfillmentService가 이미 차단하므로 정상 흐름 미발생 — 방어적)</li>
     * </ul>
     *
     * @throws IllegalStateException status가 "preparing"이 아닐 때
     */
    public void markShipping() {
        if (!"preparing".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 preparing이 아니어서 shipping으로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "shipping";
    }

    /**
     * 배송 완료 rollup — shipping → delivered.
     *
     * <p>모든 배송이 delivered 상태가 되었음을 주문 status에 rollup으로 반영한다.
     * 서비스가 rollup 조건 충족 && 주문이 "shipping"일 때만 호출한다(정합4 — 멱등 아님).
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"shipping" → "delivered" 전이 (모든 배송 완료 시 rollup)</li>
     *   <li>그 외("delivered"/"preparing"/"paid"/"cancelled"/"refunded" 등) → {@link IllegalStateException}
     *       (상위 OrderFulfillmentService가 rollup 조건을 검증하므로 정상 흐름 미발생 — 방어적)</li>
     * </ul>
     *
     * @throws IllegalStateException status가 "shipping"이 아닐 때
     */
    public void markDelivered() {
        if (!"shipping".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 shipping이 아니어서 delivered로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "delivered";
    }

    /**
     * 배송 생성 rollup — paid → preparing (첫 배송 생성 시).
     *
     * <p>배송 이행이 시작되었음을 주문 status에 rollup으로 반영한다.
     * shipping/delivered rollup은 020/021의 소관이며 본 Task에서 구현하지 않는다.
     *
     * <p>상태 전이표:
     * <ul>
     *   <li>"paid" → "preparing" 전이 (첫 배송 생성)</li>
     *   <li>"preparing" 재호출 → 멱등 no-op (추가 배송 생성 시 status 불변)</li>
     *   <li>그 외("pending"/"shipping"/"delivered"/"cancelled"/"refunded") → {@link IllegalStateException}
     *       (상위 OrderFulfillmentService가 이미 차단하므로 정상 흐름 미발생 — 방어적)</li>
     * </ul>
     *
     * @throws IllegalStateException status가 "paid"/"preparing"이 아닐 때
     */
    public void markPreparing() {
        if ("preparing".equals(this.status)) {
            // 멱등 처리 — 이미 preparing이면 no-op (추가 배송 생성 시 status 불변)
            return;
        }
        if (!"paid".equals(this.status)) {
            throw new IllegalStateException(
                    "주문 상태가 paid가 아니어서 preparing으로 전이할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = "preparing";
    }
}
