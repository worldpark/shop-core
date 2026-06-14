package com.shop.shop.order.dto;

import java.math.BigDecimal;

/**
 * 적용 가능 쿠폰 미리보기 응답 DTO.
 *
 * <p>applicable=false인 쿠폰은 reason에 사유 표기.
 * 상태 변경 없음 (읽기 전용).
 */
public record ApplicableCouponResponse(
        Long userCouponId,
        Long couponId,
        String code,
        String name,
        boolean applicable,
        BigDecimal expectedDiscount,
        String reason
) {
}
