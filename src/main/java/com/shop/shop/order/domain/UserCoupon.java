package com.shop.shop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자별 쿠폰 발급·사용 내역 Entity.
 *
 * <p>테이블: user_coupons (V1__init_schema.sql)
 * <p>created_at/updated_at 컬럼·트리거 없음 → BaseEntity 미상속 (ADR-007).
 * <p>모듈 경계 준수: userId/orderId Long 스칼라 보유 (Member/Order Entity 직접 참조 금지).
 * <p>Setter 사용 금지. 상태 변경은 Repository 조건부 UPDATE로만 수행 (경합 안전).
 */
@Entity
@Table(name = "user_coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유자 userId 스칼라.
     * user_coupons → users FK. Member Entity 직접 참조 금지.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 쿠폰 정의 couponId 스칼라.
     * user_coupons → coupons FK.
     */
    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    /**
     * 사용된 주문 orderId (nullable — null이면 미사용).
     * user_coupons → orders FK ON DELETE SET NULL.
     */
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /**
     * 사용 시각 (nullable — null이면 미사용).
     */
    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * 발급 정적 팩토리.
     *
     * <p>issuedAt=now, usedAt=null, orderId=null.
     */
    public static UserCoupon issue(long userId, long couponId, Instant now) {
        UserCoupon uc = new UserCoupon();
        uc.userId = userId;
        uc.couponId = couponId;
        uc.issuedAt = now;
        uc.usedAt = null;
        uc.orderId = null;
        return uc;
    }

    /**
     * 사용 여부 확인.
     *
     * @return usedAt != null이면 사용됨
     */
    public boolean isUsed() {
        return usedAt != null;
    }
}
