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
 * 주문 항목 옵션값 Entity.
 *
 * <p>테이블: order_item_option_values (V1__init_schema.sql)
 * <p>optionName/optionValue/sortOrder: 주문 시점 스냅샷 (변경 불가).
 * <p>시간 컬럼 없음 → BaseEntity 미상속.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 사용.
 */
@Entity
@Table(name = "order_item_option_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemOptionValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "option_value", nullable = false)
    private String optionValue;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * 주문 항목 옵션값 생성 정적 팩토리.
     *
     * @param optionName  옵션명 스냅샷 (예: "색상")
     * @param optionValue 옵션값 스냅샷 (예: "빨강")
     * @param sortOrder   정렬 순서
     * @return 새 OrderItemOptionValue 인스턴스
     */
    public static OrderItemOptionValue create(String optionName, String optionValue, int sortOrder) {
        OrderItemOptionValue ov = new OrderItemOptionValue();
        ov.optionName = optionName;
        ov.optionValue = optionValue;
        ov.sortOrder = sortOrder;
        return ov;
    }

    /**
     * OrderItem 연관관계 설정 (OrderItem.addOptionValue에서 호출).
     */
    void assignOrderItem(OrderItem orderItem) {
        this.orderItem = orderItem;
    }
}
