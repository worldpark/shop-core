package com.shop.shop.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 주문 항목 Entity.
 *
 * <p>테이블: order_items (V1__init_schema.sql)
 * <p>variantId 스칼라: variant FK ON DELETE SET NULL → nullable Long.
 * <p>updated_at 컬럼 없음 → BaseEntity 미상속.
 * <p>productName/optionLabel/unitPrice/lineAmount: 주문 시점 스냅샷 (변경 불가).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 및 의도 메서드 사용.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * variant ID 스칼라 (nullable — FK ON DELETE SET NULL).
     * variant가 삭제되면 null이 되지만 스냅샷(productName/unitPrice 등)은 보존된다.
     */
    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "option_label")
    private String optionLabel;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineAmount;

    // @BatchSize: 주문 조회 시 items만 fetch join하고(다중 bag fetch 회피 — MultipleBagFetchException),
    // optionValues는 트랜잭션 내에서 IN 배치로 일괄 로딩(N+1 회피). (OrderRepository.findWithItemsByIdAndUserId 참조)
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<OrderItemOptionValue> optionValues = new ArrayList<>();

    /**
     * 주문 항목 생성 정적 팩토리.
     *
     * <p>lineAmount = unitPrice × quantity 내부 계산.
     *
     * @param variantId   variant ID (nullable 허용 — DB SET NULL 대비)
     * @param productName 상품명 스냅샷
     * @param optionLabel 옵션 라벨 스냅샷 (nullable)
     * @param unitPrice   단가 스냅샷 (락 후 price)
     * @param quantity    주문 수량
     * @return 새 OrderItem 인스턴스
     */
    public static OrderItem create(long variantId, String productName, String optionLabel,
                                   BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.variantId = variantId;
        item.productName = productName;
        item.optionLabel = optionLabel;
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        item.lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return item;
    }

    /**
     * Order 연관관계 설정 (Order.addItem에서 호출).
     */
    void assignOrder(Order order) {
        this.order = order;
    }

    /**
     * 옵션값 추가 의도 메서드.
     *
     * @param optionValue 추가할 OrderItemOptionValue
     */
    public void addOptionValue(OrderItemOptionValue optionValue) {
        optionValues.add(optionValue);
        optionValue.assignOrderItem(this);
    }
}
