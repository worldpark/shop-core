package com.shop.shop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * inventory 소유 재고 전용 Entity — product_variants 테이블의 id·stock·is_active만 매핑.
 *
 * <p>테이블: product_variants (V1__init_schema.sql — 스키마 소유는 product/Flyway).
 * <p>이 Entity는 재고 차감 전용이며, price/sku/product_id/created_at은 inventory 책임 범위 밖이라 매핑하지 않는다.
 *
 * <p>insert 절대 금지 — 기존 row의 stock UPDATE 전용.
 * ddl-auto=validate 통과를 위해 기존 컬럼만 매핑(누락 컬럼은 validate 대상 아님).
 *
 * <p>product {@link com.shop.shop.product.domain.ProductVariant}와 같은 물리 테이블을 공유하지만
 * 두 Entity는 독립적으로 동작한다. 같은 트랜잭션에서 동시 수정 금지 — 재고 write는 이 Entity로만.
 *
 * <p>Setter 사용 금지. 정적 팩토리 불요(조회 전용). 의도 메서드 {@link #decrease(int)} 사용.
 */
@Entity
@Table(name = "product_variants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VariantStock {

    @Id
    private Long id;

    @Column(nullable = false)
    private int stock;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * 재고 차감 의도 메서드.
     *
     * <p>호출 전 검증(stock &ge; quantity)이 완료된 상태에서만 호출한다.
     * JPA dirty checking으로 stock UPDATE가 발행된다.
     *
     * @param quantity 차감할 수량 (≥ 1, 검증 후 호출)
     */
    public void decrease(int quantity) {
        this.stock -= quantity;
    }
}
