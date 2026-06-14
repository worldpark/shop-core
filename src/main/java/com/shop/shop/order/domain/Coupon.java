package com.shop.shop.order.domain;

import com.shop.shop.common.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 쿠폰 정의 Entity.
 *
 * <p>테이블: coupons (V1__init_schema.sql)
 * <p>created_at/updated_at 컬럼·트리거 없음 → BaseEntity 미상속 (ADR-007).
 * <p>Setter 사용 금지. 도메인 메서드 및 정적 팩토리 사용.
 * <p>카운터 증감은 Repository 조건부 UPDATE로만(경합 안전).
 */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /**
     * 할인 유형: "fixed" 또는 "percent".
     */
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    /**
     * 할인 값 (fixed: 금액, percent: 비율 0~100).
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    /**
     * percent 할인 최대 할인금액 상한 (nullable — 없으면 상한 없음).
     */
    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /**
     * 총 사용 한도 (nullable — null이면 무제한).
     */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    /**
     * 사용된 횟수. Repository 조건부 UPDATE로만 증감한다.
     */
    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * 쿠폰 정의 생성 정적 팩토리 (admin용).
     *
     * <p>value > 0, endsAt > startsAt 자바 레벨 사전 검증.
     */
    public static Coupon create(String code, String name, String discountType, BigDecimal value,
                                BigDecimal minOrderAmount, BigDecimal maxDiscount,
                                Instant startsAt, Instant endsAt,
                                Integer usageLimit, boolean isActive) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("쿠폰 할인 값은 0보다 커야 합니다.", HttpStatus.BAD_REQUEST);
        }
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new BusinessException("쿠폰 종료일은 시작일보다 이후여야 합니다.", HttpStatus.BAD_REQUEST);
        }
        if (!"fixed".equals(discountType) && !"percent".equals(discountType)) {
            throw new BusinessException("할인 유형은 'fixed' 또는 'percent'여야 합니다.", HttpStatus.BAD_REQUEST);
        }

        Coupon coupon = new Coupon();
        coupon.code = code;
        coupon.name = name;
        coupon.discountType = discountType;
        coupon.value = value;
        coupon.minOrderAmount = (minOrderAmount != null) ? minOrderAmount : BigDecimal.ZERO;
        coupon.maxDiscount = maxDiscount;
        coupon.startsAt = startsAt;
        coupon.endsAt = endsAt;
        coupon.usageLimit = usageLimit;
        coupon.usedCount = 0;
        coupon.isActive = isActive;
        return coupon;
    }

    /**
     * 발급 가능 여부 확인.
     *
     * <p>isActive AND startsAt ≤ now < endsAt.
     */
    public boolean isClaimable(Instant now) {
        return isActive && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /**
     * 유효기간 내 여부 확인.
     *
     * <p>startsAt ≤ now < endsAt.
     */
    public boolean isWithinPeriod(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /**
     * 최소주문금액 충족 여부 확인.
     */
    public boolean meetsMinOrder(BigDecimal itemsAmount) {
        return itemsAmount.compareTo(minOrderAmount) >= 0;
    }

    /**
     * 할인액 계산 도메인 메서드 (§1.9 규칙).
     *
     * <p>fixed: discount = min(value, itemsAmount).
     * <p>percent: raw = itemsAmount * value / 100, floored(원 단위 내림),
     *   capped = min(floored, maxDiscount) if maxDiscount != null,
     *   discount = min(capped, itemsAmount).
     * <p>결과는 setScale(2)로 numeric(12,2) 정합.
     *
     * @param itemsAmount 상품 합계금액 (≥ 0)
     * @return 할인액 (≥ 0, ≤ itemsAmount)
     */
    public BigDecimal calculateDiscount(BigDecimal itemsAmount) {
        if (itemsAmount == null || itemsAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }

        BigDecimal discount;
        if ("fixed".equals(discountType)) {
            discount = value.min(itemsAmount);
        } else {
            // percent
            BigDecimal raw = itemsAmount.multiply(value).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
            BigDecimal floored = raw; // setScale(0, FLOOR)로 원 단위 내림
            BigDecimal capped = (maxDiscount != null) ? floored.min(maxDiscount) : floored;
            discount = capped.min(itemsAmount);
        }

        // 음수 방지 (방어적)
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            discount = BigDecimal.ZERO;
        }

        return discount.setScale(2, RoundingMode.FLOOR);
    }
}
