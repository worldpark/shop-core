package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 배송 완료 전 리뷰 작성 시도 시 발생 (400).
 *
 * <p>주문 상태가 delivered가 아닌 경우(pending/paid/preparing/shipping/cancelled/refunded)에 발생한다.
 */
public class ReviewNotPurchasedException extends BusinessException {

    public ReviewNotPurchasedException() {
        super("배송 완료 후에 리뷰를 작성할 수 있습니다.", HttpStatus.BAD_REQUEST);
    }
}
