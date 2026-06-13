package com.shop.shop.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 판매자 신청 요청 DTO.
 *
 * <p>필드: 상호명 + 사업자등록번호(숫자 10자리) + 담당자 연락처 (3개 고정 — Task Constraint).
 * 사업자등록번호 외부 진위확인 없음 — 형식(숫자 10자리 패턴) 검증만.
 */
public record SellerApplicationRequest(

        @NotBlank(message = "상호명은 필수입니다.")
        String businessName,

        @NotBlank(message = "사업자등록번호는 필수입니다.")
        @Pattern(regexp = "\\d{10}", message = "사업자등록번호는 숫자 10자리입니다.")
        String businessRegistrationNumber,

        @NotBlank(message = "담당자 연락처는 필수입니다.")
        String contactPhone
) {
}
