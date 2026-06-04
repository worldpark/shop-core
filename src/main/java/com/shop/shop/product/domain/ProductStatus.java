package com.shop.shop.product.domain;

/**
 * 상품 상태 enum.
 *
 * <p>DB 저장값 = 상수명 대문자(V3 CHECK와 1:1 대응).
 * {@code @Enumerated(EnumType.STRING)}으로 상수명 그대로 저장.
 * V3 CHECK: status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'HIDDEN')
 */
public enum ProductStatus {
    DRAFT,
    ON_SALE,
    SOLD_OUT,
    HIDDEN
}
