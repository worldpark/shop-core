package com.shop.shop.order.repository;

import com.shop.shop.order.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 쿠폰 정의 리포지토리.
 *
 * <p>조건부 UPDATE 메서드는 영향행 수를 반환한다.
 * 영향행 0 = 조건 불충족(한도 소진/비활성) → 호출자가 CouponConflictException 발생.
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 코드로 쿠폰 조회.
     */
    Optional<Coupon> findByCode(String code);

    /**
     * 사용 총 한도 범위 내에서 used_count 증가 (조건부 UPDATE).
     *
     * <p>조건: is_active=true AND (usage_limit IS NULL OR used_count < usage_limit).
     * 영향행 0 이면 한도 소진 또는 비활성 → CouponConflictException.
     *
     * @param id coupon id
     * @return 영향행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 " +
           "WHERE c.id = :id AND c.isActive = true " +
           "AND (c.usageLimit IS NULL OR c.usedCount < c.usageLimit)")
    int incrementUsedCountIfWithinLimit(@Param("id") long id);

    /**
     * used_count 감소 (복원, 조건부 UPDATE).
     *
     * <p>조건: used_count > 0 (음수 방지 멱등).
     * 영향행 0이면 already=0이므로 no-op.
     *
     * @param id coupon id
     * @return 영향행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount - 1 " +
           "WHERE c.id = :id AND c.usedCount > 0")
    int decrementUsedCount(@Param("id") long id);
}
