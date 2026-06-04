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
 * 카테고리 Entity.
 *
 * <p>테이블: categories (V1__init_schema.sql)
 * <p>parent_id: 자기참조(nullable) — root 카테고리는 parent=null.
 * <p>slug: unique 제약 (uq_categories_slug).
 * <p>시간 컬럼 없음 — V1 categories에 created_at/updated_at 부재 → BaseEntity 미상속.
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #of} + 의도 노출 메서드 {@link #update} 사용.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * 정적 팩토리.
     *
     * @param name      카테고리명 (not null)
     * @param slug      URL slug (not null, unique)
     * @param parent    부모 카테고리 (null = root)
     * @param sortOrder 정렬 순서
     * @return 새 Category 인스턴스
     */
    public static Category of(String name, String slug, Category parent, int sortOrder) {
        Category category = new Category();
        category.name = name;
        category.slug = slug;
        category.parent = parent;
        category.sortOrder = sortOrder;
        return category;
    }

    /**
     * 카테고리 정보 수정 (의도 노출 메서드).
     * JPA dirty checking으로 트랜잭션 커밋 시 UPDATE가 실행된다.
     *
     * @param name      수정할 카테고리명
     * @param slug      수정할 slug
     * @param parent    수정할 부모 카테고리 (null = root)
     * @param sortOrder 수정할 정렬 순서
     */
    public void update(String name, String slug, Category parent, int sortOrder) {
        this.name = name;
        this.slug = slug;
        this.parent = parent;
        this.sortOrder = sortOrder;
    }
}
