package com.shop.shop.product.domain;

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
 * 상품 옵션 Entity.
 *
 * <p>테이블: product_options (V1__init_schema.sql)
 * <p>product_id, name — UNIQUE(product_id, name) DB 제약.
 * <p>시간 컬럼 없음 — BaseEntity 비상속.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} 사용.
 */
@Entity
@Table(name = "product_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String name;

    /**
     * 상품 옵션 생성 정적 팩토리.
     *
     * @param product 소속 상품 Entity (not null)
     * @param name    옵션명 (not null, 상품 내 유일)
     * @return 새 ProductOption 인스턴스
     */
    public static ProductOption create(Product product, String name) {
        ProductOption option = new ProductOption();
        option.product = product;
        option.name = name;
        return option;
    }
}
