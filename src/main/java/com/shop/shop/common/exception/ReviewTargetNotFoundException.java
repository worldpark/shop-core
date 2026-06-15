package com.shop.shop.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 주문 항목 미존재 또는 타인 소유 주문 항목 접근 시 발생 (404 — 존재 은닉).
 *
 * <p>IDOR 방어: 미존재와 타인 소유를 동일하게 404로 반환한다.
 */
public class ReviewTargetNotFoundException extends BusinessException {

    public ReviewTargetNotFoundException() {
        super("리뷰 대상 주문 항목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }
}
