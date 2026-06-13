package com.shop.shop.member.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 판매자 신청 반려 요청 DTO.
 *
 * <p>반려 사유(reason)는 필수다 — 빈 사유 반려는 심사 근거 부재로 허용하지 않는다.
 */
public record RejectRequest(

        @NotBlank(message = "반려 사유는 필수입니다.")
        String reason
) {
}
