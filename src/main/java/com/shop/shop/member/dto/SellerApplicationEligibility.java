package com.shop.shop.member.dto;

/**
 * 판매자 신청 자격 결과 DTO.
 *
 * <p>View facade가 web에 내리는 자격 결과. Role enum을 web에 노출하지 않고
 * boolean + String scalar로만 전달한다 (architecture-rule: 포트는 자기 모듈 소유 DTO/scalar만).
 *
 * <p>web의 SellerApplicationViewController는 {@code eligible}로만 폼/안내를 분기한다.
 * 현재 role 판정 자체는 facade 구현(SellerApplicationFacadeImpl)이 수행한다.
 */
public record SellerApplicationEligibility(
        boolean eligible,
        String reason
) {
}
