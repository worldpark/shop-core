package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 타인 상품 수정/조회 시도 시 발생하는 예외 (404).
 *
 * <p>§1.4 정보 노출 결정: 존재 은닉 관점에서 403 대신 404로 응답한다.
 * 소유자가 아닌 SELLER가 타인 상품에 접근하면 "없는 것처럼" 다루어
 * 리소스 존재 여부를 외부에서 구분 불가하게 한다.
 * ProductNotFoundException과 동일한 메시지 톤 사용.
 */
public class ProductAccessDeniedException extends BusinessException {

    public ProductAccessDeniedException(long id) {
        super("상품을 찾을 수 없습니다. id=" + id, HttpStatus.NOT_FOUND);
    }
}
