package com.shop.shop.order.dto;

import java.util.List;

/**
 * 배송 생성 요청 DTO.
 *
 * <p>{@code orderItemIds}가 null 또는 빈 목록이면 해당 주문의 미발송 항목 전부로 배송 1건을 생성한다.
 * 지정하면 해당 항목만 대상으로 한다.
 *
 * <p>검증 어노테이션이 없는 이유: 빈 목록·null 모두 "미발송 전부"로 처리하는 유효한 입력이므로
 * Bean Validation 필수 조건이 없다. 항목 유효성(미존재·타 주문 소속)은 서비스 레이어에서 검증한다.
 *
 * @param orderItemIds 배송에 포함할 주문 항목 ID 목록 (null/빈 목록 = 미발송 항목 전부)
 */
public record CreateShipmentRequest(List<Long> orderItemIds) {
}
