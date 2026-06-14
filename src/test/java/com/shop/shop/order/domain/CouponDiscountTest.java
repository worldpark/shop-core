package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coupon.calculateDiscount 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>fixed: value < itemsAmount → value 반환</li>
 *   <li>fixed: value > itemsAmount → itemsAmount 반환(음수 방지)</li>
 *   <li>percent: floor 검증(33% of 1000 = 330.00)</li>
 *   <li>percent + max_discount 상한 적용</li>
 *   <li>percent max_discount null → 상한 없음</li>
 *   <li>discount ≤ itemsAmount 보장(100% 초과 방지)</li>
 *   <li>setScale(2) 정규화</li>
 * </ul>
 */
class CouponDiscountTest {

    private static final Instant STARTS = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-12-31T23:59:59Z");

    // ============================================================
    // fixed 할인
    // ============================================================

    @Test
    @DisplayName("fixed: value < itemsAmount → value 반환")
    void fixed_valueLessThanItemsAmount_returnsValue() {
        Coupon coupon = buildFixed("1000");
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        assertThat(result).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("fixed: value > itemsAmount → itemsAmount 반환(음수 final 방지)")
    void fixed_valueGreaterThanItemsAmount_returnsItemsAmount() {
        Coupon coupon = buildFixed("10000");
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        assertThat(result).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("fixed: value == itemsAmount → itemsAmount 반환(0 final 허용)")
    void fixed_valueEqualsItemsAmount_returnsItemsAmount() {
        Coupon coupon = buildFixed("5000");
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        assertThat(result).isEqualByComparingTo("5000.00");
    }

    // ============================================================
    // percent 할인 — floor
    // ============================================================

    @Test
    @DisplayName("percent: 33% of 1000 = 330.00 (floor)")
    void percent_floor_33percentOf1000() {
        Coupon coupon = buildPercent("33", null);
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("1000"));
        assertThat(result).isEqualByComparingTo("330.00");
    }

    @Test
    @DisplayName("percent: 10% of 999 = 99.00 (floor, 소수 버림)")
    void percent_floor_10percentOf999() {
        Coupon coupon = buildPercent("10", null);
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("999"));
        // 999 * 10 / 100 = 99.9 → floor → 99
        assertThat(result).isEqualByComparingTo("99.00");
    }

    // ============================================================
    // percent 할인 — max_discount 상한
    // ============================================================

    @Test
    @DisplayName("percent: max_discount 상한 적용 — 할인 > max_discount이면 max_discount 반환")
    void percent_maxDiscountCap_applied() {
        Coupon coupon = buildPercent("50", "3000"); // 50% but max 3000
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("10000")); // 50% = 5000, capped = 3000
        assertThat(result).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("percent: max_discount null → 상한 없음")
    void percent_maxDiscountNull_noCapApplied() {
        Coupon coupon = buildPercent("50", null);
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("10000")); // 50% = 5000
        assertThat(result).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("percent: 할인 < max_discount → 상한 미적용 (할인값 그대로)")
    void percent_discountBelowMaxDiscount_notCapped() {
        Coupon coupon = buildPercent("10", "10000"); // 10% but max 10000
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000")); // 10% = 500, 상한 미적용
        assertThat(result).isEqualByComparingTo("500.00");
    }

    // ============================================================
    // discount ≤ itemsAmount 보장
    // ============================================================

    @Test
    @DisplayName("percent 100% → discount == itemsAmount (음수 final 방지)")
    void percent_100percent_discountEqualToItemsAmount() {
        Coupon coupon = buildPercent("100", null);
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        assertThat(result).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("percent 200% + no max_discount → discount = itemsAmount (음수 final 방지)")
    void percent_200percent_noMaxDiscount_discountEqualToItemsAmount() {
        Coupon coupon = buildPercent("200", null);
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        // 200% of 5000 = 10000, but capped at itemsAmount=5000
        assertThat(result).isEqualByComparingTo("5000.00");
    }

    // ============================================================
    // setScale(2) 정규화
    // ============================================================

    @Test
    @DisplayName("결과는 항상 scale=2 (numeric(12,2) 정합)")
    void result_alwaysScale2() {
        Coupon coupon = buildFixed("1000");
        BigDecimal result = coupon.calculateDiscount(new BigDecimal("5000"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("itemsAmount=0 → discount=0.00")
    void itemsAmountZero_returnsZero() {
        Coupon coupon = buildFixed("1000");
        BigDecimal result = coupon.calculateDiscount(BigDecimal.ZERO);
        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private Coupon buildFixed(String value) {
        return Coupon.create("CODE", "테스트쿠폰", "fixed", new BigDecimal(value),
                BigDecimal.ZERO, null, STARTS, ENDS, null, true);
    }

    private Coupon buildPercent(String value, String maxDiscount) {
        BigDecimal max = (maxDiscount != null) ? new BigDecimal(maxDiscount) : null;
        return Coupon.create("CODE", "테스트쿠폰", "percent", new BigDecimal(value),
                BigDecimal.ZERO, max, STARTS, ENDS, null, true);
    }
}
