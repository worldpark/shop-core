package com.shop.shop.order.controller;

import com.shop.shop.order.dto.ApplicableCouponResponse;
import com.shop.shop.order.dto.CouponClaimRequest;
import com.shop.shop.order.dto.UserCouponResponse;
import com.shop.shop.order.service.CouponServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 쿠폰 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/coupons/**} hasRole("CONSUMER").
 * 비로그인 → 401, 비CONSUMER → 403.
 *
 * <p>레이어: CouponRestController → CouponServiceResponse → CouponService → Repository.
 * 비즈니스 로직 없음 — ServiceResponse에 전적으로 위임.
 */
@Tag(name = "coupon", description = "쿠폰 — 발급·내 쿠폰함·적용 가능 조회 (CONSUMER 이상)")
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponRestController {

    private final CouponServiceResponse couponServiceResponse;

    /**
     * 쿠폰 발급 (claim).
     * POST /api/v1/coupons → 201 Created
     *
     * @param auth JWT SecurityContext
     * @param req  쿠폰 코드
     * @return 발급된 쿠폰 정보
     */
    @Operation(summary = "쿠폰 발급")
    @PostMapping
    public ResponseEntity<UserCouponResponse> claim(
            Authentication auth,
            @Valid @RequestBody CouponClaimRequest req) {
        UserCouponResponse response = couponServiceResponse.claim(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 쿠폰함 조회.
     * GET /api/v1/coupons → 200 OK
     *
     * @param auth JWT SecurityContext
     * @return 보유 쿠폰 목록 (미사용/사용/만료 구분)
     */
    @GetMapping
    public ResponseEntity<List<UserCouponResponse>> getMyCoupons(Authentication auth) {
        List<UserCouponResponse> response = couponServiceResponse.getMyCoupons(auth);
        return ResponseEntity.ok(response);
    }

    /**
     * 적용 가능 쿠폰 미리보기.
     * GET /api/v1/coupons/applicable → 200 OK
     *
     * <p>현재 장바구니 기준 보유 미사용 쿠폰별 적용 가능 여부 + 예상 할인액.
     * 상태 변경 없음 (읽기 전용).
     *
     * @param auth JWT SecurityContext
     * @return 적용 가능 쿠폰 목록
     */
    @GetMapping("/applicable")
    public ResponseEntity<List<ApplicableCouponResponse>> getApplicable(Authentication auth) {
        List<ApplicableCouponResponse> response = couponServiceResponse.getApplicable(auth);
        return ResponseEntity.ok(response);
    }
}
