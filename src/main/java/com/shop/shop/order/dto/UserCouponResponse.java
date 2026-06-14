package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 사용자 쿠폰함 항목 응답 DTO.
 *
 * <p>Entity 미노출. 만료 여부는 now >= endsAt로 계산.
 */
public record UserCouponResponse(
        Long userCouponId,
        Long couponId,
        String code,
        String name,
        String discountType,
        BigDecimal value,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscount,
        Instant startsAt,
        Instant endsAt,
        boolean used,
        Instant usedAt,
        boolean expired
) {
}
