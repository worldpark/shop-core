package com.shop.shop.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 관리자 쿠폰 정의 생성 요청 DTO.
 */
public record AdminCouponCreateRequest(
        @NotBlank(message = "쿠폰 코드를 입력해 주세요.") String code,
        @NotBlank(message = "쿠폰 이름을 입력해 주세요.") String name,
        @NotNull(message = "할인 유형을 입력해 주세요.")
        @Pattern(regexp = "fixed|percent", message = "할인 유형은 'fixed' 또는 'percent'여야 합니다.")
        String discountType,
        @NotNull(message = "할인 값을 입력해 주세요.")
        @Positive(message = "할인 값은 0보다 커야 합니다.")
        BigDecimal value,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscount,
        @NotNull(message = "시작일을 입력해 주세요.") Instant startsAt,
        @NotNull(message = "종료일을 입력해 주세요.") Instant endsAt,
        Integer usageLimit,
        Boolean isActive
) {
}
