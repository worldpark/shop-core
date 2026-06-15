package com.shop.shop.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 재고 조정 요청 DTO.
 *
 * <p>Bean Validation 1차 검증. 서비스에서 delta=0·memo 공란 2차 검증.
 *
 * @param delta 부호 있는 조정량 (양수=증가, 음수=감소, 0 불허 — 서비스 2차 검증)
 * @param memo  조정 사유 (필수)
 */
public record StockAdjustmentRequest(
        @NotNull(message = "조정량(delta)은 필수입니다.") Integer delta,
        @NotBlank(message = "조정 사유(memo)는 필수입니다.") String memo
) {
}
