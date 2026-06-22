package com.shop.shop.order.spi;

import com.shop.shop.order.dto.UserCouponResponse;

import java.util.List;

/**
 * 사용자 쿠폰함 View 전용 facade (published port).
 *
 * <p>web 모듈의 CouponViewController가 order 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 order 내부 {@code service} 패키지에 위치한다.
 *
 * <p>email을 인자로 받아 내부에서 {@code member.spi.MemberDirectory}로 userId로 변환한다.
 * (OrderFacade 선례와 동형 — 015)
 *
 * <p>의존 방향: web → order.spi 단방향. order는 web을 참조하지 않는다.
 */
public interface CouponFacade {

    /**
     * 내 쿠폰함 조회 — email 기반.
     *
     * <p>email → userId(MemberDirectory) → CouponService.getMyCoupons(userId) 위임.
     * 미사용/사용/만료 구분 포함.
     *
     * @param email View principal email (JWT 쿠키 인증, emailPrincipalFactory)
     * @return 보유 쿠폰 목록 (UserCouponResponse, 031 DTO 재사용)
     */
    List<UserCouponResponse> getMyWallet(String email);

    /**
     * 쿠폰 코드 발급 — email 기반.
     *
     * <p>email → userId(MemberDirectory) → CouponService.claim(userId, code) 위임.
     * View는 redirect 후 결과 메시지만 사용하므로 반환값 없음.
     *
     * @param email View principal email
     * @param code  쿠폰 코드
     * @throws com.shop.shop.common.exception.CouponNotFoundException     코드 미존재
     * @throws com.shop.shop.common.exception.CouponNotClaimableException 비활성/유효기간 외
     * @throws com.shop.shop.common.exception.CouponAlreadyOwnedException 1인 1매 중복 발급
     */
    void claim(String email, String code);
}
