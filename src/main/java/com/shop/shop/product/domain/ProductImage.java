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
 * 상품 이미지 Entity.
 *
 * <p>테이블: product_images (V1__init_schema.sql)
 * <p>product_images 테이블에 created_at/updated_at 컬럼이 없으므로 {@code BaseEntity}를 상속하지 않는다.
 *
 * <p>대표 이미지 제약 (partial unique index {@code uq_product_images_primary}):
 * is_primary=true인 행은 product_id당 1개만 허용된다.
 * 대표 전이 시 기존 대표 해제({@link #unmarkPrimary}) → saveAndFlush → 신규 대표 지정({@link #markPrimary}) 순서를 반드시 지켜야 한다.
 *
 * <p>DB에는 storageKey만 저장한다 (host·절대경로 금지 — static-asset-rule).
 *
 * <p>Setter 사용 금지. 정적 팩토리 {@link #create} + 의도 메서드 사용.
 */
@Entity
@Table(name = "product_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** storageKey — DB 저장값. host·절대경로 포함 금지. 예: products/10/uuid.jpg */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** 정렬 순서. 낮을수록 앞에 표시된다. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 대표 이미지 여부. product_id당 1개만 true 허용 (partial unique index). */
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /**
     * 상품 이미지 생성 정적 팩토리.
     *
     * @param product    소속 상품 Entity
     * @param storageKey 저장된 파일의 storageKey
     * @param sortOrder  정렬 순서
     * @param isPrimary  대표 이미지 여부
     * @return 새 ProductImage 인스턴스
     */
    public static ProductImage create(Product product, String storageKey, int sortOrder, boolean isPrimary) {
        ProductImage image = new ProductImage();
        image.product = product;
        image.storageKey = storageKey;
        image.sortOrder = sortOrder;
        image.isPrimary = isPrimary;
        return image;
    }

    /**
     * 대표 이미지로 지정한다.
     */
    public void markPrimary() {
        this.isPrimary = true;
    }

    /**
     * 대표 이미지 지정을 해제한다.
     *
     * <p>partial unique index 충돌 회피를 위해 기존 대표를 먼저 해제하고
     * saveAndFlush한 뒤 신규 대표를 지정해야 한다.
     */
    public void unmarkPrimary() {
        this.isPrimary = false;
    }

    /**
     * 정렬 순서를 변경한다 (JPA dirty checking).
     *
     * @param sortOrder 새 정렬 순서
     */
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
