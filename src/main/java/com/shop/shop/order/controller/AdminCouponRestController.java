package com.shop.shop.order.controller;

import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;
import com.shop.shop.order.service.CouponServiceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 쿠폰 REST 컨트롤러.
 *
 * <p>인가: SecurityConfig REST 체인 {@code /api/v1/admin/**} hasRole("ADMIN").
 * CONSUMER → 403, 비로그인 → 401.
 *
 * <p>레이어: AdminCouponRestController → CouponServiceResponse → CouponService → Repository.
 * 비즈니스 로직 없음 — ServiceResponse에 전적으로 위임.
 */
@RestController
@RequestMapping("/api/v1/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponRestController {

    private final CouponServiceResponse couponServiceResponse;

    /**
     * 쿠폰 정의 생성 (최소).
     * POST /api/v1/admin/coupons → 201 Created
     *
     * @param req 쿠폰 정의 생성 요청
     * @return 생성된 쿠폰 정의
     */
    @PostMapping
    public ResponseEntity<AdminCouponResponse> createCoupon(
            @Valid @RequestBody AdminCouponCreateRequest req) {
        AdminCouponResponse response = couponServiceResponse.createDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
