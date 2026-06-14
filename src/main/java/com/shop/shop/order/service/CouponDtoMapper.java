package com.shop.shop.order.service;

import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.dto.ApplicableCouponResponse;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.service.CouponService.ApplicableCouponView;
import com.shop.shop.order.service.CouponService.CouponDefResult;
import com.shop.shop.order.service.CouponService.UserCouponView;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 내부 결과 record → DTO 변환 (package-private).
 *
 * <p>OrderDtoMapper 선례 — Entity 미노출, 변환만 담당.
 */
@Component
class CouponDtoMapper {

    UserCouponResponse toUserCouponResponse(UserCouponView view) {
        return new UserCouponResponse(
                view.userCouponId(),
                view.couponId(),
                view.code(),
                view.name(),
                view.discountType(),
                view.value(),
                view.minOrderAmount(),
                view.maxDiscount(),
                view.startsAt(),
                view.endsAt(),
                view.used(),
                view.usedAt(),
                view.expired()
        );
    }

    ApplicableCouponResponse toApplicableCouponResponse(ApplicableCouponView view) {
        return new ApplicableCouponResponse(
                view.userCouponId(),
                view.couponId(),
                view.code(),
                view.name(),
                view.applicable(),
                view.expectedDiscount(),
                view.reason()
        );
    }

    AdminCouponResponse toAdminCouponResponse(CouponDefResult result) {
        return new AdminCouponResponse(
                result.id(),
                result.code(),
                result.name(),
                result.discountType(),
                result.value(),
                result.minOrderAmount(),
                result.maxDiscount(),
                result.startsAt(),
                result.endsAt(),
                result.usageLimit(),
                result.usedCount(),
                result.isActive()
        );
    }
}
