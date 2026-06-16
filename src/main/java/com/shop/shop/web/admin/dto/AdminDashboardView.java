package com.shop.shop.web.admin.dto;

import java.math.BigDecimal;

/**
 * 관리자 통계 대시보드 뷰 모델 DTO.
 *
 * <p>3개 지표(유저 이용률·상품 판매율·환불율)를 각각 {@link Metric} 중첩 record로 표현한다.
 * 비율({@link Metric#ratioPercent})은 분모가 0일 경우 {@code null}(데이터 없음 표시용).
 *
 * <p>web 모듈 전용 — Entity·도메인 enum 직접 참조 없음.
 *
 * @param memberActivity  유저 이용률 지표 (최근 30일 접속 활성 회원 / 전체 활성 회원)
 * @param productSales    상품 판매율 지표 (최근 30일 판매된 게시 상품 / 전체 게시 상품)
 * @param refundRate      환불율 지표 (최근 30일 환불 주문수 / 최근 30일 전체 주문수)
 * @param periodLabel     기준 기간 표기 (예: "최근 30일")
 */
public record AdminDashboardView(
        Metric memberActivity,
        Metric productSales,
        Metric refundRate,
        String periodLabel
) {

    /**
     * 단일 비율 지표.
     *
     * @param ratioPercent 비율(%) — scale 1, HALF_UP. 분모가 0이면 {@code null}.
     * @param numerator    분자
     * @param denominator  분모
     */
    public record Metric(
            BigDecimal ratioPercent,
            long numerator,
            long denominator
    ) {
    }
}
