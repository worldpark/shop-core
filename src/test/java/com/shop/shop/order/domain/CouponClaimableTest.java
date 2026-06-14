package com.shop.shop.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coupon.isClaimable 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>startsAt 포함 (≥) — 활성</li>
 *   <li>endsAt 배타 (&lt;) — 마감</li>
 *   <li>비활성 false</li>
 *   <li>유효기간 전 false</li>
 *   <li>유효기간 내 활성 true</li>
 * </ul>
 */
class CouponClaimableTest {

    private static final Instant STARTS = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-06-30T23:59:59Z");

    @Test
    @DisplayName("startsAt 포함(=) — isClaimable true")
    void isClaimable_atStartTime_returnsTrue() {
        Coupon coupon = buildActive();
        assertThat(coupon.isClaimable(STARTS)).isTrue();
    }

    @Test
    @DisplayName("endsAt 배타(=) — isClaimable false")
    void isClaimable_atEndTime_returnsFalse() {
        Coupon coupon = buildActive();
        assertThat(coupon.isClaimable(ENDS)).isFalse();
    }

    @Test
    @DisplayName("비활성 쿠폰 — isClaimable false")
    void isClaimable_inactive_returnsFalse() {
        Coupon coupon = Coupon.create("CODE", "테스트", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, STARTS, ENDS, null, false);
        Instant midPeriod = Instant.parse("2026-06-15T12:00:00Z");
        assertThat(coupon.isClaimable(midPeriod)).isFalse();
    }

    @Test
    @DisplayName("유효기간 전 — isClaimable false")
    void isClaimable_beforePeriod_returnsFalse() {
        Coupon coupon = buildActive();
        Instant beforeStart = Instant.parse("2026-05-31T23:59:59Z");
        assertThat(coupon.isClaimable(beforeStart)).isFalse();
    }

    @Test
    @DisplayName("유효기간 내 활성 — isClaimable true")
    void isClaimable_withinPeriodAndActive_returnsTrue() {
        Coupon coupon = buildActive();
        Instant midPeriod = Instant.parse("2026-06-15T12:00:00Z");
        assertThat(coupon.isClaimable(midPeriod)).isTrue();
    }

    @Test
    @DisplayName("유효기간 이후 — isClaimable false")
    void isClaimable_afterPeriod_returnsFalse() {
        Coupon coupon = buildActive();
        Instant afterEnd = Instant.parse("2026-07-01T00:00:00Z");
        assertThat(coupon.isClaimable(afterEnd)).isFalse();
    }

    private Coupon buildActive() {
        return Coupon.create("CODE", "테스트", "fixed", BigDecimal.ONE,
                BigDecimal.ZERO, null, STARTS, ENDS, null, true);
    }
}
