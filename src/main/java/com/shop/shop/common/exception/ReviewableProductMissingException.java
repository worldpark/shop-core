package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * variant 삭제로 product_id 도출 불가 시 발생 (400).
 *
 * <p>order_item.variant_id가 null(ON DELETE SET NULL)인 경우 product_id를 확인할 수 없어
 * 리뷰 대상 상품을 특정할 수 없다.
 */
public class ReviewableProductMissingException extends BusinessException {

    public ReviewableProductMissingException() {
        super("삭제된 상품은 리뷰할 수 없습니다.", HttpStatus.BAD_REQUEST);
    }
}
