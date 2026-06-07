package com.shop.shop.cart.domain;

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

import java.time.Instant;

/**
 * 장바구니 항목 Entity.
 *
 * <p>테이블: cart_items (V1__init_schema.sql)
 * <p>(cart_id, variant_id) UNIQUE 제약 — uq_cart_items_cart_variant.
 * <p>variantId 스칼라: product variant Entity 직접 참조 금지(architecture-rule 모듈 경계).
 * <p>updated_at 컬럼 없음 → BaseEntity 미상속(ProductVariant 선례와 동일).
 * <p>added_at: DB default now() 읽기전용 매핑(insertable=false).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create(Cart, long, int)},
 * 수량 절대값 변경 의도 메서드 {@link #changeQuantity(int)} 사용.
 * 재담기 증가는 Entity 메서드가 아니라 atomic UPDATE 쿼리로 처리한다.
 */
@Entity
@Table(name = "cart_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /**
     * variant ID 스칼라.
     * cart → product variant Entity 직접 참조를 피하기 위해 Long 스칼라로 보유한다.
     */
    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(nullable = false)
    private int quantity;

    /**
     * 최초 담은 시점 — DB default now(), 읽기전용.
     * 재담기 증가 시 addedAt은 변경하지 않는다(최초 담은 시점 보존).
     */
    @Column(name = "added_at", insertable = false, updatable = false)
    private Instant addedAt;

    /**
     * 장바구니 항목 생성 정적 팩토리.
     *
     * @param cart      소속 장바구니
     * @param variantId 상품 variant ID
     * @param quantity  수량 (≥ 1)
     * @return 새 CartItem 인스턴스
     */
    public static CartItem create(Cart cart, long variantId, int quantity) {
        CartItem item = new CartItem();
        item.cart = cart;
        item.variantId = variantId;
        item.quantity = quantity;
        return item;
    }

    /**
     * 수량 절대값 변경 (수량 변경 last-write-wins용).
     *
     * <p>비관적 락 없이 호출 시점 절대값으로 설정한다.
     * 재담기 증가는 이 메서드가 아니라 atomic UPDATE 쿼리({@code increaseQuantityWithinStock})로 처리한다.
     *
     * @param quantity 변경할 수량 (≥ 1)
     */
    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }
}
