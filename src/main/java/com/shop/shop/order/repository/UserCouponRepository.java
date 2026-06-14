package com.shop.shop.order.repository;

import com.shop.shop.order.domain.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 사용자별 쿠폰 발급·사용 내역 리포지토리.
 *
 * <p>조건부 UPDATE 메서드는 영향행 수를 반환한다.
 */
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    /**
     * 사용자의 발급된 쿠폰 목록 (발급일시 내림차순).
     */
    List<UserCoupon> findByUserIdOrderByIssuedAtDesc(long userId);

    /**
     * 소유권 포함 단건 조회 (미보유/타인 → empty → CouponNotFoundException).
     */
    Optional<UserCoupon> findByIdAndUserId(long id, long userId);

    /**
     * 미사용 쿠폰만 조회 (applicable 미리보기용, 부분 인덱스 idx_user_coupons_user_unused 활용).
     */
    List<UserCoupon> findByUserIdAndUsedAtIsNull(long userId);

    /**
     * 미사용 상태에서만 사용 처리 (조건부 UPDATE, 1회용 보장).
     *
     * <p>조건: id AND user_id AND used_at IS NULL.
     * 영향행 0이면 동시 사용 경합 패자 또는 이미 사용됨 → CouponConflictException.
     *
     * @param id      user_coupon id
     * @param userId  소유자 userId
     * @param orderId 연결할 주문 id
     * @param now     used_at에 기록할 현재 시각
     * @return 영향행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE UserCoupon uc SET uc.usedAt = :now, uc.orderId = :orderId " +
           "WHERE uc.id = :id AND uc.userId = :userId AND uc.usedAt IS NULL")
    int markUsedIfUnused(@Param("id") long id,
                         @Param("userId") long userId,
                         @Param("orderId") long orderId,
                         @Param("now") Instant now);

    /**
     * 주문에 연결된 user_coupon 복원 (조건부 UPDATE, 멱등).
     *
     * <p>조건: order_id=:orderId AND used_at IS NOT NULL (이미 복원된 경우 no-op).
     *
     * @param orderId 복원할 주문 id
     * @return 영향행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE UserCoupon uc SET uc.usedAt = NULL, uc.orderId = NULL " +
           "WHERE uc.orderId = :orderId AND uc.usedAt IS NOT NULL")
    int restoreByOrderId(@Param("orderId") long orderId);

    /**
     * 주문 id로 user_coupon 조회 (복원 시 couponId 확보용).
     *
     * <p>주문당 최대 1매이므로 Optional 단건.
     */
    Optional<UserCoupon> findByOrderId(long orderId);
}
