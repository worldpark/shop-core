package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * SKU 전역 중복 시 발생하는 예외 (409 Conflict).
 *
 * <p>V3 불변식: SKU는 전역 유일이어야 한다. 수정 시 자기 자신 제외.
 */
public class DuplicateSkuException extends BusinessException {

    public DuplicateSkuException(String sku) {
        super("이미 사용 중인 SKU입니다.", HttpStatus.CONFLICT);
    }
}
