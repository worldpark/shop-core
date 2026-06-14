package com.shop.shop.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 주문 생성 요청 DTO (REST + View 공용).
 *
 * <p>배송지 필수 4종(recipient/phone/postcode/address1) + 선택 address2.
 * userCouponId: 선택 필드 — null이면 쿠폰 미적용(기존 흐름 동일).
 * @NotBlank 검증 실패 → 400(MethodArgumentNotValid).
 */
public record OrderCreateRequest(
        @NotBlank(message = "수령인 이름을 입력해 주세요.") String recipient,
        @NotBlank(message = "전화번호를 입력해 주세요.") String phone,
        @NotBlank(message = "우편번호를 입력해 주세요.") String postcode,
        @NotBlank(message = "주소를 입력해 주세요.") String address1,
        String address2,
        Long userCouponId
) {
}
