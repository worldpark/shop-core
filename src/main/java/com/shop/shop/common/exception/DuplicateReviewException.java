package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 동일 주문 항목에 리뷰를 중복 작성 시도할 때 발생 (409).
 *
 * <p>order_item_id UNIQUE(uq_reviews_order_item_id) 위반에서 파생된다.
 * 구매 1건당 1리뷰 제약.
 */
public class DuplicateReviewException extends BusinessException {

    public DuplicateReviewException() {
        super("이미 작성한 리뷰가 있습니다.", HttpStatus.CONFLICT);
    }
}
