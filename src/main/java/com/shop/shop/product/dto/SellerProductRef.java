package com.shop.shop.product.dto;

/**
 * 판매자 상품 참조 DTO (variant 관리 화면용).
 *
 * <p>상품 ID와 이름만 담는 경량 참조 객체.
 */
public record SellerProductRef(
        long productId,
        String name
) {
}
