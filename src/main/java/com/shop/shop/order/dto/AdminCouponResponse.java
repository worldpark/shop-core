package com.shop.shop.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 관리자 쿠폰 정의 응답 DTO.
 */
public record AdminCouponResponse(
        Long id,
        String code,
        String name,
        String discountType,
        BigDecimal value,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscount,
        Instant startsAt,
        Instant endsAt,
        Integer usageLimit,
        int usedCount,
        boolean isActive
) {
}
