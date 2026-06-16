package com.shop.shop.product.dto;

import java.math.BigDecimal;

/**
 * 판매자 상품 참조 DTO (variant 관리 화면용).
 *
 * <p>상품 ID·이름·기본가격을 담는 경량 참조 객체.
 */
public record SellerProductRef(
        long productId,
        String name,
        BigDecimal basePrice
) {
}
