package com.shop.shop.common.exception;

/**
 * 구매 불가 variant(비활성·미존재·상품 비공개 등) 담기·수량변경 시 발생하는 예외 (400).
 *
 * <p>기본 생성자 → BusinessException(message) → HttpStatus.BAD_REQUEST(400).
 */
public class VariantNotPurchasableException extends BusinessException {

    public VariantNotPurchasableException() {
        super("구매할 수 없는 상품 옵션입니다.");
    }

    public VariantNotPurchasableException(String message) {
        super(message);
    }
}
