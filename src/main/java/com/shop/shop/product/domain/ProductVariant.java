package com.shop.shop.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 상품 variant Entity (구매 단위 SKU).
 *
 * <p>테이블: product_variants (V1__init_schema.sql)
 * <p>updated_at 컬럼이 없으므로 {@code BaseEntity}를 상속하지 않는다.
 * created_at은 DB 소유 읽기전용 매핑(insertable=false, updatable=false).
 *
 * <p>variant_values 조인 테이블을 {@code @ManyToMany}로 소유한다.
 * 별도 VariantValue Entity 없음 — database_design.md §4.2 SSOT 준수.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create}, 수정 의도 메서드 {@link #update} 사용.
 */
@Entity
@Table(name = "product_variants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "variant_values",
            joinColumns = @JoinColumn(name = "variant_id"),
            inverseJoinColumns = @JoinColumn(name = "option_value_id")
    )
    private Set<OptionValue> optionValues = new HashSet<>();

    /**
     * variant 생성 정적 팩토리.
     *
     * @param product      소속 상품 Entity
     * @param sku          SKU (전역 유일)
     * @param price        가격 (≥ 0)
     * @param stock        재고 (≥ 0)
     * @param isActive     활성 여부
     * @param optionValues 선택 옵션값 집합
     * @return 새 ProductVariant 인스턴스
     */
    public static ProductVariant create(Product product, String sku, BigDecimal price,
                                        int stock, boolean isActive, Set<OptionValue> optionValues) {
        ProductVariant variant = new ProductVariant();
        variant.product = product;
        variant.sku = sku;
        variant.price = price;
        variant.stock = stock;
        variant.isActive = isActive;
        variant.optionValues = new HashSet<>(optionValues);
        return variant;
    }

    /**
     * variant 수정 의도 메서드 (JPA dirty checking).
     *
     * @param sku          수정할 SKU
     * @param price        수정할 가격 (≥ 0)
     * @param stock        수정할 재고 (≥ 0)
     * @param isActive     수정할 활성 여부
     * @param optionValues 수정할 옵션값 집합
     */
    public void update(String sku, BigDecimal price, int stock,
                       boolean isActive, Set<OptionValue> optionValues) {
        this.sku = sku;
        this.price = price;
        this.stock = stock;
        this.isActive = isActive;
        this.optionValues = new HashSet<>(optionValues);
    }
}
