package com.shop.shop.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 배송 시작 요청 DTO.
 *
 * <p>POST /api/v1/admin/shipments/{shipmentId}/ship 요청 본문.
 * carrier/trackingNumber 누락 시 Bean Validation → MethodArgumentNotValidException → 400.
 *
 * @param carrier        택배사명 (필수, 공백 불가)
 * @param trackingNumber 운송장 번호 (필수, 공백 불가)
 */
public record ShipRequest(
        @NotBlank(message = "택배사명을 입력해주세요.")
        String carrier,

        @NotBlank(message = "운송장 번호를 입력해주세요.")
        String trackingNumber
) {
}
