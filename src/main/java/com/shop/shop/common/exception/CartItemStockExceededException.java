package com.shop.shop.common.exception;

/**
 * 신규 담기·재담기·수량변경 시 stock 초과로 발생하는 예외 (400).
 *
 * <p>stock 초과를 409로 분기하지 않는다 — 검증 실패 일괄 400(Task 명시).
 */
public class CartItemStockExceededException extends BusinessException {

    public CartItemStockExceededException() {
        super("재고 수량을 초과하여 담을 수 없습니다.");
    }

    public CartItemStockExceededException(String message) {
        super(message);
    }
}
