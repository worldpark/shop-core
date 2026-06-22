package com.shop.shop.order.spi;

import com.shop.shop.order.dto.AdminCouponCreateRequest;
import com.shop.shop.order.dto.AdminCouponResponse;

import java.util.List;

/**
 * 관리자 쿠폰 관리 View 전용 facade (published port).
 *
 * <p>web 모듈의 AdminCouponViewController가 order 도메인 내부 Service·Entity를 직접 참조하지 않도록
 * 이 facade를 경유한다. 구현체는 order 내부 {@code service} 패키지에 위치한다.
 * (AdminCategoryFacade — 041 선례와 동형)
 *
 * <p>의존 방향: web → order.spi 단방향. order는 web을 참조하지 않는다.
 */
public interface AdminCouponFacade {

    /**
     * 전체 쿠폰 정의 목록 조회 — 종료일 내림차순.
     *
     * <p>CouponRepository.findAllByOrderByEndsAtDesc() → AdminCouponResponse 매핑.
     * used_count / usage_limit / is_active 포함. 읽기 전용.
     *
     * @return 전체 쿠폰 목록 (AdminCouponResponse, 031 DTO 재사용)
     */
    List<AdminCouponResponse> list();

    /**
     * 쿠폰 정의 생성 — CouponService.createDefinition 위임.
     *
     * <p>web→spi 경계에 web 폼 타입 전달 금지 규칙상 AdminCouponCreateRequest(order/dto)로 전달.
     * View 컨트롤러(AdminCouponViewController)가 form → AdminCouponCreateRequest 변환 담당.
     *
     * @param req 쿠폰 생성 요청 (order/dto, 031 재사용)
     * @return 생성된 쿠폰 정의 응답
     * @throws com.shop.shop.common.exception.DuplicateCouponCodeException 코드 중복 (409)
     */
    AdminCouponResponse create(AdminCouponCreateRequest req);
}
