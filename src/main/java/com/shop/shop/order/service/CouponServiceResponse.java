package com.shop.shop.order.service;

import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.dto.ApplicableCouponResponse;
import com.shop.shop.order.dto.CouponClaimRequest;
import com.shop.shop.order.dto.UserCouponResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 쿠폰 REST 응답 조합 전용 ServiceResponse 레이어.
 *
 * <p>View/Scheduler/EventListener에서는 사용하지 않는다 (architecture-rule).
 * 비즈니스 로직 없음 — CouponService에 전적으로 위임.
 *
 * <p>REST principal: JWT 필터 후 {@code (long) authentication.getPrincipal()}.
 *
 * <p>레이어: CouponRestController → CouponServiceResponse → CouponService → Repository
 */
@Service
@RequiredArgsConstructor
public class CouponServiceResponse {

    private final CouponService couponService;
    private final CouponDtoMapper dtoMapper;

    /**
     * 쿠폰 발급 — REST 전용.
     *
     * @param auth JWT SecurityContext
     * @param req  발급 요청
     * @return 발급된 쿠폰 응답
     */
    public UserCouponResponse claim(Authentication auth, CouponClaimRequest req) {
        long userId = (long) auth.getPrincipal();
        CouponService.UserCouponResult result = couponService.claim(userId, req.code());
        // 발급 후 쿠폰 상세 포함 응답 구성 — getMyCoupons 중 해당 항목 반환
        List<CouponService.UserCouponView> views = couponService.getMyCoupons(userId);
        return views.stream()
                .filter(v -> v.userCouponId() == result.userCouponId())
                .findFirst()
                .map(dtoMapper::toUserCouponResponse)
                .orElseGet(() -> buildMinimalResponse(result));
    }

    /**
     * 내 쿠폰함 조회 — REST 전용.
     */
    public List<UserCouponResponse> getMyCoupons(Authentication auth) {
        long userId = (long) auth.getPrincipal();
        List<CouponService.UserCouponView> views = couponService.getMyCoupons(userId);
        return views.stream()
                .map(dtoMapper::toUserCouponResponse)
                .collect(Collectors.toList());
    }

    /**
     * 적용 가능 쿠폰 미리보기 — REST 전용.
     */
    public List<ApplicableCouponResponse> getApplicable(Authentication auth) {
        long userId = (long) auth.getPrincipal();
        List<CouponService.ApplicableCouponView> views = couponService.getApplicable(userId);
        return views.stream()
                .map(dtoMapper::toApplicableCouponResponse)
                .collect(Collectors.toList());
    }

    /**
     * 쿠폰 정의 생성 — REST 전용 (ADMIN).
     */
    public AdminCouponResponse createDefinition(AdminCouponCreateRequest req) {
        CouponService.CouponDefResult result = couponService.createDefinition(req);
        return dtoMapper.toAdminCouponResponse(result);
    }

    private UserCouponResponse buildMinimalResponse(CouponService.UserCouponResult result) {
        // fallback: 발급 직후 coupon 조회 실패 시 최소 정보만 반환 (정상 경로 아님)
        return new UserCouponResponse(
                result.userCouponId(), result.couponId(),
                null, null, null, null, null, null, null, null,
                false, null, false
        );
    }
}
