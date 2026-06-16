package com.shop.shop.web.product.dto;

import java.math.BigDecimal;

/**
 * 판매자 상품 현황 페이지 행 뷰 모델 DTO.
 *
 * <p>상품 1행 = 상품 기본 정보 + 총재고 + 판매 집계(수량·매출).
 * status는 String — web 모듈은 ProductStatus enum을 직접 참조하지 않는다.
 * variant가 없거나 삭제된 variant만 있는 상품은 salesQty=0, revenue=ZERO.
 *
 * <p>삭제된 variant의 판매분은 집계에서 제외된다(variantId NULL 행 — IDOR 구조상 한계).
 *
 * @param productId  상품 ID
 * @param name       상품명
 * @param status     상품 상태 문자열 (DRAFT/ON_SALE/SOLD_OUT/HIDDEN)
 * @param totalStock 모든 variant stock 합계 (현재 시점)
 * @param salesQty   누적 판매 수량 (paid/preparing/shipping/delivered 주문 기준)
 * @param revenue    누적 매출 (lineAmount 합계, 동일 기준)
 */
public record SellerProductStatsRow(
        long productId,
        String name,
        String status,
        long totalStock,
        long salesQty,
        BigDecimal revenue
) {
}
