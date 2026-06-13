package com.shop.shop.member.spi;

import com.shop.shop.member.dto.SellerApplicationSummaryResponse;
import org.springframework.data.domain.Page;

/**
 * 관리자 View 전용 판매자 신청 facade (published port).
 *
 * <p>web 모듈의 AdminSellerApplicationViewController가 member 도메인 내부 Service·Entity를
 * 직접 참조하지 않도록 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>포트 시그니처는 web 타입(Authentication/Form)을 받지 않고 scalar(String/long)/DTO만 노출한다
 * (architecture-rule: "포트는 자기 모듈 소유 DTO/scalar만 노출").
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface AdminSellerApplicationFacade {

    /**
     * 판매자 신청 목록 조회 — 상태 필터(null/빈 문자열=전체) + 페이지네이션.
     *
     * @param status status 필터 문자열 (null/빈 문자열 = 전체)
     * @param page   페이지 번호 (0 기반)
     * @param size   페이지 크기
     * @return DTO 페이지
     */
    Page<SellerApplicationSummaryResponse> search(String status, int page, int size);

    /**
     * 판매자 신청 승인 — adminEmail → userId 해석 후 서비스 위임.
     *
     * @param adminEmail    심사 ADMIN 이메일 (View form login principal = email)
     * @param applicationId 신청 ID
     */
    void approve(String adminEmail, long applicationId);

    /**
     * 판매자 신청 반려 — adminEmail → userId 해석 후 서비스 위임.
     *
     * @param adminEmail    심사 ADMIN 이메일
     * @param applicationId 신청 ID
     * @param reason        반려 사유
     */
    void reject(String adminEmail, long applicationId, String reason);
}
