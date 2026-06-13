package com.shop.shop.member.spi;

import com.shop.shop.member.dto.SellerApplicationEligibility;
import com.shop.shop.member.dto.SellerApplicationRequest;
import com.shop.shop.member.dto.SellerApplicationResponse;

import java.util.Optional;

/**
 * 신청자 View 전용 판매자 신청 facade (published port).
 *
 * <p>web 모듈의 SellerApplicationViewController가 member 도메인 내부 Service·Entity·Role enum을
 * 직접 참조하지 않도록 이 facade를 경유한다. 구현체는 member 내부 {@code service} 패키지에 위치한다.
 *
 * <p>포트 시그니처는 Role enum을 노출하지 않는다 — 자격 결과를 scalar/DTO로만 반환한다
 * (architecture-rule: "포트는 자기 모듈 소유 DTO/scalar만 노출").
 *
 * <p>의존 방향: web → member.spi (단방향). member는 web을 참조하지 않는다.
 */
public interface SellerApplicationFacade {

    /**
     * 신청 자격 확인 — Role enum 비노출, scalar/DTO로만 결과 반환.
     *
     * <p>현재 role이 CONSUMER가 아니거나 PENDING 신청이 이미 존재하면 {@code eligible=false}.
     *
     * @param email 신청자 이메일 (View login principal = email)
     * @return 자격 결과 DTO ({@code eligible=true}이면 신청 가능)
     */
    SellerApplicationEligibility checkEligibility(String email);

    /**
     * 판매자 신청 제출 — 자격은 서비스가 재검증(409).
     *
     * @param email 신청자 이메일
     * @param req   신청 요청 DTO
     */
    void apply(String email, SellerApplicationRequest req);

    /**
     * 본인 최신 신청 Optional 조회 — View /me 화면용 (없으면 안내 화면).
     *
     * <p>REST /me와 달리 없으면 404를 throw하지 않고 빈 Optional을 반환한다.
     * View는 Optional.empty() 시 "신청 내역 없음 + 신청 링크" 안내를 렌더한다 (§1.7).
     *
     * @param email 신청자 이메일
     * @return 신청 응답 DTO Optional
     */
    Optional<SellerApplicationResponse> findMyApplication(String email);
}
