package com.shop.shop.product.dto;

import com.shop.shop.product.domain.Product;

import java.math.BigDecimal;

/**
 * 상품 수정 화면 View DTO (읽기 전용).
 *
 * <p>web 모듈이 Product Entity·{@code ProductStatus} enum을 직접 참조하지 않도록 한다.
 * status는 {@code ProductStatus.name()} 문자열로 변환해 노출한다.
 *
 * <p>Entity 직접 노출 금지 — {@link #from(Product)} 정적 팩토리로만 생성한다.
 *
 * @param categoryId  카테고리 ID (null = 미분류)
 * @param name        상품명
 * @param description 상품 설명 (null 허용)
 * @param basePrice   기본 가격
 * @param status      상품 상태 문자열 (DRAFT/ON_SALE/SOLD_OUT/HIDDEN)
 */
public record ProductFormView(
        Long categoryId,
        String name,
        String description,
        BigDecimal basePrice,
        String status
) {

    /**
     * {@code Product} Entity로부터 View DTO 생성.
     * status는 {@code ProductStatus.name()}(대문자 문자열)으로 변환.
     * category는 null 허용(미분류 상품).
     *
     * @param product 상품 Entity
     * @return ProductFormView DTO
     */
    public static ProductFormView from(Product product) {
        return new ProductFormView(
                product.getCategory() == null ? null : product.getCategory().getId(),
                product.getName(),
                product.getDescription(),
                product.getBasePrice(),
                product.getStatus().name()
        );
    }
}
