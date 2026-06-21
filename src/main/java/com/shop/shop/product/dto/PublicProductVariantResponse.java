package com.shop.shop.product.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공개 상품 variant 응답 DTO.
 *
 * <p>sku·stock 수치 미노출. available(boolean)만 노출.
 * active 필드 불필요 — 상세에서는 활성 variant만 반환하므로.
 *
 * <p>optionLabel: 이 variant가 어떤 옵션 조합인지 사람이 읽는 라벨("옵션명: 값 / 옵션명: 값").
 * 옵션이 없는 상품의 variant는 빈 문자열. 구매 옵션 선택 UI에서 가격과 함께 노출한다.
 */
public record PublicProductVariantResponse(
        long variantId,
        BigDecimal price,
        List<Long> optionValueIds,
        String optionLabel,
        boolean available
) {
}
