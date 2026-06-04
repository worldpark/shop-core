package com.shop.shop.product.domain;

import com.shop.shop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;

/**
 * 상품 Entity.
 *
 * <p>테이블: products (V1__init_schema.sql + V3__product_status_and_owner.sql)
 * <p>category_id: nullable(V1 ON DELETE SET NULL) — 미분류 상품 허용.
 * <p>owner_id: V3 신규 추가. 판매자/소유자 식별 — users.id 참조(스칼라 — 모듈 경계 준수).
 * <p>base_price: numeric(12,2) — BigDecimal 전 구간 사용(부동소수점 금지).
 * <p>status: @Enumerated(STRING) — V3 CHECK(대문자) 정합.
 * <p>created_at/updated_at: BaseEntity 상속 — DB 소유 읽기전용.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} + 의도 노출 메서드 {@link #update} 사용.
 * <p>owner_id는 long 스칼라 — product→member Entity 직접 참조 회피(architecture-rule 모듈 경계).
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /**
     * 소유자(판매자) ID — users.id 참조 스칼라.
     * product→member Entity 직접 참조를 피하기 위해 Long 스칼라로 보유한다.
     * FK 무결성은 DB(REFERENCES users(id))가 보장한다.
     */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    /**
     * 상품 등록 정적 팩토리.
     * status는 항상 DRAFT로 강제한다 (등록 시 기본값).
     *
     * @param ownerId     소유자 userId (long)
     * @param category    카테고리 (null 허용)
     * @param name        상품명 (not null)
     * @param description 상품 설명 (null 허용)
     * @param basePrice   기본 가격 (≥ 0, BigDecimal)
     * @return 새 Product 인스턴스 (status = DRAFT)
     */
    public static Product create(long ownerId, Category category, String name,
                                  String description, BigDecimal basePrice) {
        Product product = new Product();
        product.ownerId = ownerId;
        product.category = category;
        product.name = name;
        product.description = description;
        product.basePrice = basePrice;
        product.status = ProductStatus.DRAFT;
        return product;
    }

    /**
     * 상품 정보 수정 (의도 노출 메서드).
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param category    수정할 카테고리 (null 허용)
     * @param name        수정할 상품명
     * @param description 수정할 상품 설명 (null 허용)
     * @param basePrice   수정할 기본 가격 (≥ 0)
     * @param status      수정할 상태
     */
    public void update(Category category, String name, String description,
                       BigDecimal basePrice, ProductStatus status) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.status = status;
    }
}
